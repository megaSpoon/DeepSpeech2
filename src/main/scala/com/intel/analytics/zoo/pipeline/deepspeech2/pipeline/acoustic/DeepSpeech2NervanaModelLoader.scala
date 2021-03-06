package com.intel.analytics.zoo.pipeline.deepspeech2.pipeline.acoustic

/*
 * Copyright 2016 The BigDL Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import com.intel.analytics.bigdl._
import com.intel.analytics.bigdl.nn._
import com.intel.analytics.bigdl.nn.abstractnn.AbstractModule
import com.intel.analytics.bigdl.tensor.TensorNumericMath.TensorNumeric
import com.intel.analytics.bigdl.tensor.{Storage, Tensor}
import com.intel.analytics.bigdl.utils.{Engine, Table}
import org.apache.log4j.{Level, Logger}
import org.apache.spark.SparkContext
import org.apache.spark.sql.SparkSession

import scala.collection.mutable.ArrayBuffer
import scala.language.existentials
import scala.reflect.ClassTag

class DeepSpeech2NervanaModelLoader[T : ClassTag] (depth: Int = 1, isPaperVersion: Boolean = false)
                                                  (implicit ev: TensorNumeric[T]) {

  /**
    * The configuration of convolution for dp2.
    */
  val nInputPlane = 1
  val nOutputPlane = 1152
  val kW = 11
  val kH = 13
  val dW = 3
  val dH = 1
  val padW = 5
  val padH = 0
  val conv = SpatialConvolution(nInputPlane, nOutputPlane,
    kW, kH, dW, dH, padW, padH)

  val nOutputDim = 2
  val outputHDim = 3
  val outputWDim = 4
  val inputSize = nOutputPlane
  val hiddenSize = nOutputPlane
  val nChar = 29

  /**
    * append BiRNN layers to the deepspeech model.
    * @param inputSize
    * @param hiddenSize
    * @param curDepth
    * @return
    */
//  def addBRNN(inputSize: Int, hiddenSize: Int, isCloneInput: Boolean, curDepth: Int): Module[T] = {
//    val layers = Sequential()
//
//    if (curDepth == 1) {
//      layers
//        .add(ConcatTable()
//          .add(Identity[T]())
//          .add(Identity[T]()))
//    } else {
//      layers
//        .add(BifurcateSplitTable[T](3))
//    }
//    layers
//      .add(ParallelTable[T]()
//        .add(TimeDistributed[T](Linear[T](inputSize, hiddenSize, withBias = false).setName("i2h_left" + depth)))
//        .add(TimeDistributed[T](Linear[T](inputSize, hiddenSize, withBias = false).setName("i2h_right" + depth))))
//      .add(JoinTable[T](2, 2))
//      .add(BatchNormalizationDS[T](hiddenSize * 2, eps = 0.001))
//      .add(BiRecurrentDS[T](JoinTable[T](2, 2).asInstanceOf[AbstractModule[Table, Tensor[T], T]], isCloneInput = false)
//        .add(RnnCellDS[T](inputSize, hiddenSize, HardTanh[T](0, 20, true))).setName("birnn" + depth))
//    layers
//  }
  def addBRNN(inputSize: Int, hiddenSize: Int, curDepth: Int)
  : Module[T] = {
    val layers = Sequential()
    if (isPaperVersion) {
      layers
        .add(TimeDistributed[T](Linear[T](inputSize, hiddenSize, withBias = false)))
        .add(BatchNormalizationDS[T](hiddenSize, eps = 0.001))
        .add(BiRecurrentDS[T](isCloneInput = true)
          .add(RnnCellDS[T](hiddenSize, hiddenSize, HardTanh[T](0, 20, true))).setName("birnn" + depth))
    } else {
      if (curDepth == 1) {
        layers
          .add(ConcatTable()
            .add(Identity[T]())
            .add(Identity[T]()))
      } else {
        layers
          .add(BifurcateSplitTable[T](3))
      }
      layers
        .add(ParallelTable[T]()
          .add(TimeDistributed[T](Linear[T](inputSize, hiddenSize, withBias = false)))
          .add(TimeDistributed[T](Linear[T](inputSize, hiddenSize, withBias = false))))
        .add(JoinTable[T](2, 2))
        .add(BatchNormalizationDS[T](hiddenSize * 2, eps = 0.001))
        .add(BiRecurrentDS[T](JoinTable[T](2, 2).asInstanceOf[AbstractModule[Table, Tensor[T], T]], isCloneInput = false)
          .add(RnnCellDS[T](hiddenSize, hiddenSize, HardTanh[T](0, 20, true))).setName("birnn" + depth))
    }
  layers
}

  val brnn = Sequential()
  var i = 1
  while (i <= depth) {
    if (i == 1) {
      brnn.add(addBRNN(inputSize, hiddenSize, i))
    } else {
      brnn.add(addBRNN(hiddenSize, hiddenSize, i))
    }
    i += 1
  }

  val linear1 = TimeDistributed[T](Linear[T](hiddenSize * 2, hiddenSize, withBias = false))
  val linear2 = TimeDistributed[T](Linear[T](hiddenSize, nChar, withBias = false))

  /**
    * The deep speech2 model.
    *****************************************************************************************
    *
    *   Convolution -> ReLU -> BiRNN (9 layers) -> Linear -> ReLUClip (HardTanh) -> Linear
    *
    *****************************************************************************************
    */
  val model = Sequential[T]()
    .add(conv)
    .add(ReLU[T]())
    .add(Transpose(Array((nOutputDim, outputWDim), (outputHDim, outputWDim))))
    .add(Squeeze(4))
    .add(brnn)
    .add(linear1)
    .add(HardTanh[T](0, 20, true))
    .add(linear2)

  def reset(): Unit = {
    conv.weight.fill(ev.fromType[Float](0.0F))
    conv.bias.fill(ev.fromType[Float](0.0F))
  }

  // TODO: merge this and convert
  def setConvWeight(weights: Array[T]): Unit = {
    val temp = Tensor[T](Storage(weights), 1, Array(1, 1152, 1, 13, 11))
    require(temp.nElement() == conv.weight.nElement(),
      "parameter's size doesn't match")
    conv.weight.set(Storage[T](weights), 1, conv.weight.size())
  }

  /**
    * load in the nervana's dp2 BiRNN model parameters
    * @param weights
    */
  // TODO: merge this and convertBiRNN
  def setBiRNNWeight(weights: Array[Array[T]]): Unit = {
    val parameters = brnn.parameters()._1
    // six tensors per brnn layer
    val numOfParams = 6
    for (i <- 0 until depth) {
      var offset = 1
      for (j <- 0 until numOfParams) {
        val length = parameters(i * numOfParams + j).nElement()
        val size = parameters(i * numOfParams + j).size
        require(length == size.product,
          "parameter's size doesn't match")
        parameters(i * numOfParams + j).set(Storage[T](weights(i)), offset, size)
        offset += length
      }
    }
  }

  /**
    * load in the nervana's dp2 Affine model parameters
    * @param weights
    * @param num
    */
  // TODO: merge this and convertLinear
  def setLinear0Weight(weights: Array[T], num: Int): Unit = {
    if (num == 0) {
      require(linear1.parameters()._1(0).nElement() == weights.length,
        "parameter's size doesn't match")
      linear1.parameters()._1(0)
        .set(Storage[T](weights), 1, Array(1152, 2304))
    } else {
      require(linear2.parameters()._1(0).nElement() == weights.length,
        "parameter's size doesn't match")
      linear2.parameters()._1(0)
        .set(Storage[T](weights), 1, Array(29, 1152))
    }
  }
}

object DeepSpeech2NervanaModelLoader {

  val logger = Logger.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    evaluateModel()
  }

  def evaluateModel(): Unit = {
    Logger.getLogger("org").setLevel(Level.WARN)

    val conf = Engine.createSparkConf()
    conf.setMaster("local").setAppName("DeepSpeechInferenceExample")
    val sc = new SparkContext(conf)
    val spark = SparkSession.builder().master("local[6]").appName("test").getOrCreate()
    import spark.implicits._
    val dp2 = loadModel[Float](spark.sparkContext)
    logger.info("run the model ..")

    logger.info("load in inputs and expectOutputs ..")
    val inputPath = "data/inputdata.txt"
    val nervanaOutputPath = "data/output.txt"
    val inputs = spark.sparkContext.textFile(inputPath)
      .map(_.toFloat).collect()
    val expectOutputs = spark.sparkContext.textFile(nervanaOutputPath)
      .map(_.split(',').map(_.toFloat)).flatMap(t => t).collect()
    evaluate(dp2, inputs, convert(expectOutputs, 1000))
  }

  private def evaluate(model: Module[Float], inputs: Array[Float], expectOutputs: Array[Float]): Unit = {

    /**
      ********************************************************
      *  For my small test, I cut sample size to 198,
      *  please modify it according to your input sample.
      ********************************************************
      */
    val input = Tensor[Float](Storage(inputs), 1, Array(1, 1, 13, inputs.length / 13))
    val output = model.forward(input).toTensor[Float]
    println(output)

    var idx = 0
    var accDiff = 0.0
    output.apply1(x => {
      if (x == 0) {
        require(math.abs(x - expectOutputs(idx)) < 1e-2,
          "output does not concord to each other " +
            s"x = ${x}, expectX = ${expectOutputs(idx)}, idx = ${idx}")
        accDiff += math.abs(x - expectOutputs(idx))
      } else {
        require(math.abs(x - expectOutputs(idx)) / x < 1e-1,
          "output does not concord to each other " +
            s"x = ${x}, expectX = ${expectOutputs(idx)}, idx = ${idx}")
        accDiff += math.abs(x - expectOutputs(idx))
      }
      idx += 1
      x
    })

    logger.info("model inference finish!")
    logger.info("total relative error is : " + accDiff)
  }

  def loadModel[T: ClassTag](sc: SparkContext)
                            (implicit  ev: TensorNumeric[T]): Module[T] = {


    /**
      ***************************************************************************
      *   Please configure your file path here:
      *   There should be 9 txt files for birnn.
      *   e.g. "/home/ywan/Documents/data/deepspeech/layer1.txt"
      ***************************************************************************
      */

    val convPath = "data/conv.txt"
    val birnnPath = "data/layer"
    val linear1Path = "data/linear0.txt"
    val linear2Path = "data/linear1.txt"

    /**
      *********************************************************
      *    set the depth to be 9
      *    timeSeqLen is the final output sequence length
      *    The original timeSeqLen = 1000
      *    for my small test, I set it to be 66.
      *********************************************************
      */
    val depth = 9
    val convFeatureSize = 1152
    val birnnFeatureSize = 1152
    val linear1FeatureSize = 2304
    val linear2FeatureSize = 1152

    /**
      *************************************************************************
      *    Loading model weights
      *    1. conv
      *    2. birnn
      *    3. linear1
      *    4. linear2
      *************************************************************************
      */

    logger.info("load in conv weights ..")
    val convWeights = sc.textFile(convPath)
      .flatMap(_.split(",").map(v => ev.fromType(v.toFloat))).collect()

    logger.info("load in birnn weights ..")
    val weightsBirnn = new Array[Array[T]](depth)
    for (i <- 0 until depth) {
      val birnnOrigin = sc.textFile(birnnPath + i + ".txt")
        .flatMap(_.split(",").map(v => ev.fromType(v.toFloat))).collect()
      weightsBirnn(i) = convertBiRNN[T](birnnOrigin, birnnFeatureSize)
    }

    logger.info("load in linear1 weights ..")
    val linearOrigin0 = sc.textFile(linear1Path)
      .flatMap(_.split(",").map(v => ev.fromType(v.toFloat))).collect()
    val weightsLinear0 = convertLinear[T](linearOrigin0, linear1FeatureSize)

    logger.info("load in linear2 weights ..")
    val linearOrigin1 = sc.textFile(linear2Path)
      .flatMap(_.split(",").map(v => ev.fromType(v.toFloat))).collect()
    val weightsLinear1 = convertLinear[T](linearOrigin1, linear2FeatureSize)

    /**
      **************************************************************************
      *  set all the weights to the model and run the model
      *  dp2.evaluate()
      **************************************************************************
      */
    val dp2 = new DeepSpeech2NervanaModelLoader[T](depth)
    dp2.reset()
    dp2.setConvWeight(convert(convWeights, convFeatureSize))
    dp2.setBiRNNWeight(weightsBirnn)
    // TODO: check size
    dp2.setLinear0Weight(weightsLinear0, 0)
    dp2.setLinear0Weight(weightsLinear1, 1)

    dp2.model
  }

  def convert[T: ClassTag](origin: Array[T], channelSize: Int): Array[T] = {
    val channel = channelSize
    val buffer = new ArrayBuffer[T]()
    val groups = origin.grouped(channelSize).toArray

    for(i <- 0 until channel)
      for (j <- 0 until groups.length)
        buffer += groups(j)(i)
    buffer.toArray
  }

  def convertLinear[T: ClassTag](origin: Array[T], channelSize: Int): Array[T] = {
    val channel = channelSize
    val buffer = new ArrayBuffer[T]()
    val groups = origin.grouped(channelSize).toArray

    for (j <- 0 until groups.length)
      for(i <- 0 until channel)
        buffer += groups(j)(i)
    buffer.toArray
  }

  def convertBiRNN[T: ClassTag](origin: Array[T], channelSize: Int): Array[T] = {
    val nIn = channelSize
    val nOut = channelSize
    val heights = 2 * (nIn + nOut + 1)
    val widths = nOut

    val buffer = new ArrayBuffer[T]()
    val groups = origin.grouped(nOut).toArray

    /**
      * left-to-right rnn U, W, and bias
      */
//
//    for (i <- 0 until nIn) {
//      for (j <- 0 until nOut) {
//        buffer += groups(i)(j)
//      }
//    }
//
//    for (i <- 2 * nIn until 2 * nIn + nOut) {
//      for (j <- 0 until nOut) {
//        buffer += groups(i)(j)
//      }
//    }
//
//    for (i <- 2 * (nIn + nOut + 1) - 2 until 2 * (nIn + nOut + 1) - 1) {
//      for (j <- 0 until nOut) {
//        buffer += groups(i)(j)
//      }
//    }
//
//    for (i <- nIn until 2 * nIn) {
//      for (j <- 0 until nOut) {
//        buffer += groups(i)(j)
//      }
//    }
//
//    for (i <- (2 * nIn + nOut) until (2 * nIn + 2 * nOut)) {
//      for (j <- 0 until nOut) {
//        buffer += groups(i)(j)
//      }
//    }
//
//    for (i <- 2 * (nIn + nOut + 1) - 1 until 2 * (nIn + nOut + 1)) {
//      for (j <- 0 until nOut) {
//        buffer += groups(i)(j)
//      }
//    }
    /**
      * left-to-right i2h
      * right-to-left i2h
      *
      * left-to-right h2h
      * left-to-right bias
      *
      * right-to-left h2h
      * right-to-left bias
      */
    for (i <- 0 until 2 * nIn + nOut) {
      for (j <- 0 until nOut) {
        buffer += groups(i)(j)
      }
    }

    for (i <- 2 * (nIn + nOut + 1) - 2 until 2 * (nIn + nOut + 1) - 1) {
      for (j <- 0 until nOut) {
        buffer += groups(i)(j)
      }
    }

    for (i <- (2 * nIn + nOut) until (2 * nIn + 2 * nOut)) {
      for (j <- 0 until nOut) {
        buffer += groups(i)(j)
      }
    }

    for (i <- 2 * (nIn + nOut + 1) - 1 until 2 * (nIn + nOut + 1)) {
      for (j <- 0 until nOut) {
        buffer += groups(i)(j)
      }
    }
    buffer.toArray
  }
}
