package v

import chisel3._
import chisel3.experimental.{SerializableModule, SerializableModuleParameter}
import chisel3.util._
import chisel3.util.experimental.decode.DecodeBundle

object LaneParameter {
  implicit def rwP: upickle.default.ReadWriter[LaneParameter] = upickle.default.macroRW
}

/** Parameter for [[Lane]].
  * @param vLen VLEN
  * @param dataPathWidth width of data path, can be 32 or 64, decides the memory bandwidth.
  * @param laneNumber how many lanes in the vector processor
  * @param chainingSize how many instructions can be chained
  * @param crossLaneVRFWriteEscapeQueueSize The cross lane write queue designed for latch data from cross lane interconnection,
  *                                         if VRF backpress on ring, this queue will be used for latch data from ring,
  *                                         in case of additional latency from ring.
  *                                         TODO: cover the queue full.
  */
case class LaneParameter(
  vLen:                             Int,
  datapathWidth:                    Int,
  laneNumber:                       Int,
  chainingSize:                     Int,
  crossLaneVRFWriteEscapeQueueSize: Int)
    extends SerializableModuleParameter {

  /** 1 in MSB for instruction order. */
  val instructionIndexBits: Int = log2Ceil(chainingSize) + 1

  /** maximum of lmul, defined in spec. */
  val lmulMax: Int = 8

  /** see [[VParameter.sewMin]] */
  val sewMin: Int = 8

  /** The datapath is divided into groups based on SEW.
    * The current implement is calculating them in multiple cycles.
    * TODO: we must parallelize it as much as possible since it highly affects the computation bandwidth.
    */
  val dataPathByteWidth: Int = datapathWidth / sewMin

  /** maximum of vl. */
  val vlMax: Int = vLen * lmulMax / sewMin

  /** width of [[vlMax]]
    * `+1` is for vl being 0 to vlMax(not vlMax - 1).
    * we use less than for comparing, rather than less equal.
    */
  val vlMaxBits: Int = log2Ceil(vlMax) + 1

  /** how many group does a single register have.
    *
    * in each lane, for one vector register, it is divided into groups with size of [[datapathWidth]]
    */
  val singleGroupSize: Int = vLen / datapathWidth / laneNumber

  /** for each instruction, the maximum number of groups to execute. */
  val groupNumberMax: Int = singleGroupSize * lmulMax

  /** used as the LSB index of VRF access
    *
    * TODO: uarch doc the arrangement of VRF: {reg index, offset}
    *
    * for each number in table below, it represent a [[datapathWidth]]
    * {{{
    *         lane0 | lane1 | ...                                   | lane8
    * offset0    0  |    1  |    2  |    3  |    4  |    5  |    6  |    7
    * offset1    8  |    9  |   10  |   11  |   12  |   13  |   14  |   15
    * offset2   16  |   17  |   18  |   19  |   20  |   21  |   22  |   23
    * offset3   24  |   25  |   26  |   27  |   28  |   29  |   30  |   31
    * }}}
    */
  val vrfOffsetBits: Int = log2Ceil(singleGroupSize)

  /** +1 in MSB for comparing to next group number.
    * we don't use `vl-1` is because if the mask of last group is all 0, it should be jumped through.
    * so we directly compare the next group number with the MSB of `vl`.
    */
  val groupNumberBits: Int = log2Ceil(groupNumberMax + 1)

  /** Half of [[datapathWidth]], this is used in the cross-lane accessing logic. */
  val halfDatapathWidth: Int = datapathWidth / 2

  /** uarch TODO: instantiate logic, add to each slot
    * logic, add, shift, multiple, divide, other
    *
    * TODO: use Seq().size to calculate
    */
  val executeUnitNum: Int = 6

  /** hardware width of [[laneNumber]]. */
  val laneNumberBits: Int = log2Ceil(laneNumber)

  /** hardware width of [[datapathWidth]]. */
  val datapathWidthBits: Int = log2Ceil(datapathWidth)

  /** see [[VParameter.maskGroupWidth]] */
  val maskGroupWidth: Int = datapathWidth

  /** see [[VParameter.maskGroupSize]] */
  val maskGroupSize: Int = vLen / datapathWidth

  /** hardware width of [[maskGroupSize]]. */
  val maskGroupSizeBits: Int = log2Ceil(maskGroupSize)

  /** Parameter for [[VRF]] */
  def vrfParam: VRFParam = VRFParam(vLen, laneNumber, datapathWidth, chainingSize)

  /** Parameter for [[LaneShifter]]. */
  def shifterParameter: LaneShifterParameter = LaneShifterParameter(datapathWidth)

  /** Parameter for [[LaneMul]]. */
  def mulParam: LaneMulParam = LaneMulParam(datapathWidth, vLen)
}

class Lane(val parameter: LaneParameter) extends Module with SerializableModule[LaneParameter] {

  /** laneIndex is a IO constant for D/I and physical implementations. */
  val laneIndex: UInt = IO(Input(UInt(parameter.laneNumberBits.W)))
  dontTouch(laneIndex)

  /** VRF Read Interface.
    * TODO: use mesh
    */
  val readBusPort: RingPort[ReadBusData] = IO(new RingPort(new ReadBusData(parameter)))

  /** VRF Write Interface.
    * TODO: use mesh
    */
  val writeBusPort: RingPort[WriteBusData] = IO(new RingPort(new WriteBusData(parameter)))

  /** request from [[V]] to [[Lane]] */
  val laneRequest: DecoupledIO[LaneRequest] = IO(Flipped(Decoupled(new LaneRequest(parameter))))

  /** CSR Interface. */
  val csrInterface: LaneCsrInterface = IO(Input(new LaneCsrInterface(parameter.vlMaxBits)))

  /** to mask unit or LSU */
  val laneResponse: ValidIO[LaneDataResponse] = IO(Valid(new LaneDataResponse(parameter)))

  /** feedback from [[V]] for [[laneResponse]] */
  val laneResponseFeedback: ValidIO[SchedulerFeedback] = IO(Flipped(Valid(new SchedulerFeedback(parameter))))

  // for LSU and V accessing lane, this is not a part of ring, but a direct connection.
  // TODO: learn AXI channel, reuse [[vrfReadAddressChannel]] and [[vrfWriteChannel]].
  val vrfReadAddressChannel: DecoupledIO[VRFReadRequest] = IO(
    Flipped(
      Decoupled(
        new VRFReadRequest(parameter.vrfParam.regNumBits, parameter.vrfOffsetBits, parameter.instructionIndexBits)
      )
    )
  )
  val vrfReadDataChannel: UInt = IO(Output(UInt(parameter.datapathWidth.W)))
  val vrfWriteChannel: DecoupledIO[VRFWriteRequest] = IO(
    Flipped(
      Decoupled(
        new VRFWriteRequest(
          parameter.vrfParam.regNumBits,
          parameter.vrfOffsetBits,
          parameter.instructionIndexBits,
          parameter.datapathWidth
        )
      )
    )
  )

  /** for each instruction in the slot, response to top when instruction is finished in this lane. */
  val instructionFinished: UInt = IO(Output(UInt(parameter.chainingSize.W)))

  /** V0 update in the lane should also update [[V.v0]] */
  val v0Update: ValidIO[V0Update] = IO(Valid(new V0Update(parameter)))

  /** input of mask data */
  val maskInput: UInt = IO(Input(UInt(parameter.maskGroupWidth.W)))

  /** select which mask group. */
  val maskSelect: UInt = IO(Output(UInt(parameter.maskGroupSizeBits.W)))

  /** because of load store index EEW, is complicated for lane to calculate whether LSU is finished.
    * let LSU directly tell each lane it is finished.
    */
  val lsuLastReport: UInt = IO(Input(UInt(parameter.chainingSize.W)))

  /** for RaW, VRF should wait for buffer to be empty. */
  val lsuVRFWriteBufferClear: Bool = IO(Input(Bool()))
  val maskUnitFlushVrf:       Bool = IO(Input(Bool()))

  // TODO: remove
  dontTouch(writeBusPort)

  /** VRF instantces. */
  val vrf: VRF = Module(new VRF(parameter.vrfParam))

  /** TODO: review later
    */
  val maskGroupedOrR: UInt = VecInit(
    maskInput.asBools
      .grouped(parameter.dataPathByteWidth)
      .toSeq
      .map(
        VecInit(_).asUInt.orR
      )
  ).asUInt

  // mask logic 因为是1bit作为element,所以需要有mask destination一样的处理
  /** xxx   xxx     xxxxx
    * head  body    tail
    */
  val vlTail: UInt = csrInterface.vl(parameter.datapathWidthBits - 1, 0)
  val vlBody: UInt =
    csrInterface.vl(parameter.datapathWidthBits + parameter.laneNumberBits - 1, parameter.datapathWidthBits)
  val vlHead: UInt = csrInterface.vl(parameter.vlMaxBits - 1, parameter.datapathWidthBits + parameter.laneNumberBits)
  val lastGroupMask = scanRightOr(UIntToOH(vlTail)) >> 1
  val dataPathMisaligned = vlTail.orR
  val maskeDataGroup = (vlHead ## vlBody) - !dataPathMisaligned
  val lastLaneIndexForMaskLogic:  UInt = maskeDataGroup(parameter.laneNumberBits - 1, 0)
  val isLastLaneForMaskLogic:     Bool = lastLaneIndexForMaskLogic === laneIndex
  val lastGroupCountForMaskLogic: UInt = maskeDataGroup >> parameter.laneNumberBits

  val fullMask: UInt = (-1.S(parameter.datapathWidth.W)).asUInt

  /** the slot is occupied by instruction */
  val slotOccupied: Vec[Bool] = RegInit(VecInit(Seq.fill(parameter.chainingSize)(false.B)))

  /** read from VRF vs1 for VFU */
  val source1: Vec[UInt] = RegInit(VecInit(Seq.fill(parameter.chainingSize)(0.U(parameter.datapathWidth.W))))

  /** read from VRF vs2 for VFU */
  val source2: Vec[UInt] = RegInit(VecInit(Seq.fill(parameter.chainingSize)(0.U(parameter.datapathWidth.W))))

  /** read from VRF rd for VFU */
  val source3: Vec[UInt] = RegInit(VecInit(Seq.fill(parameter.chainingSize)(0.U(parameter.datapathWidth.W))))

  /** execution result, write to VRF,
    * or goes to [[V]] for complex instructions like reduce
    */
  val result: Vec[UInt] = RegInit(VecInit(Seq.fill(parameter.chainingSize)(0.U(parameter.datapathWidth.W))))
  // 跨lane写的额外用寄存器存储执行的结果和mask

  // wait data for EEW = 2*SEW
  // TODO: do we need to switch to remote waiting?
  val crossWriteResultLSBHalf: UInt = RegInit(0.U(parameter.datapathWidth.W))
  val crossWriteMaskLSBHalf:   UInt = RegInit(0.U((parameter.dataPathByteWidth / 2).W))
  val crossWriteResultMSBHalf: UInt = RegInit(0.U(parameter.datapathWidth.W))
  val crossWriteMaskMSBHalf:   UInt = RegInit(0.U((parameter.dataPathByteWidth / 2).W))

  val maskFormatResult: UInt = RegInit(0.U(parameter.datapathWidth.W))
  val ffoIndexReg:      UInt = RegInit(0.U(log2Ceil(parameter.vLen / 8).W))
  val reduceResult:     Vec[UInt] = RegInit(VecInit(Seq.fill(parameter.chainingSize)(0.U(parameter.datapathWidth.W))))

  /** arbiter for VRF write
    * 1 for [[vrfWriteChannel]]
    */
  val vrfWriteArbiter: Vec[ValidIO[VRFWriteRequest]] = Wire(
    Vec(
      parameter.chainingSize + 1,
      Valid(
        new VRFWriteRequest(
          parameter.vrfParam.regNumBits,
          parameter.vrfOffsetBits,
          parameter.instructionIndexBits,
          parameter.datapathWidth
        )
      )
    )
  )
  vrfWriteArbiter(parameter.chainingSize).valid := vrfWriteChannel.valid
  vrfWriteArbiter(parameter.chainingSize).bits := vrfWriteChannel.bits

  /** writing to VRF
    * 1 for [[vrfWriteChannel]]
    * 1 for [[crossLaneWriteQueue]]
    */
  val vrfWriteFire: UInt = Wire(UInt((parameter.chainingSize + 2).W))
  vrfWriteChannel.ready := vrfWriteFire(parameter.chainingSize)

  /** for each slot, assert when it is asking [[V]] to change mask */
  val slotMaskRequestVec: Vec[ValidIO[UInt]] = Wire(
    Vec(
      parameter.chainingSize,
      Valid(UInt(parameter.maskGroupSizeBits.W))
    )
  )

  /** which slot wins the arbitration for requesting mask. */
  val maskRequestFireOH: UInt = Wire(UInt(parameter.chainingSize.W))

  /** read from VRF, it will go to ring in the next cycle.
    * from [[vrfReadRequest]](1)
    */
  val crossReadLSBOut: UInt = RegInit(0.U(parameter.datapathWidth.W))

  /** read from VRF, it will go to ring in the next cycle.
    * from [[vrfReadRequest]](2)
    */
  val crossReadMSBOut: UInt = RegInit(0.U(parameter.datapathWidth.W))

  /** read from Bus, it will try to write to VRF in the next cycle. */
  val crossReadLSBIn: UInt = RegInit(0.U(parameter.datapathWidth.W))

  /** read from Bus, it will try to write to VRF in the next cycle. */
  val crossReadMSBIn: UInt = RegInit(0.U(parameter.datapathWidth.W))

  /** FSM control for each slot. */
  val slotControl: Vec[InstControlRecord] =
    RegInit(
      VecInit(
        Seq.fill(parameter.chainingSize)(0.U.asTypeOf(new InstControlRecord(parameter)))
      )
    )

  /** VRF read request for each slot,
    * 3 is for [[source1]] [[source2]] [[source3]]
    */
  val vrfReadRequest: Vec[Vec[DecoupledIO[VRFReadRequest]]] = Wire(
    Vec(
      parameter.chainingSize,
      Vec(
        3,
        Decoupled(
          new VRFReadRequest(parameter.vrfParam.regNumBits, parameter.vrfOffsetBits, parameter.instructionIndexBits)
        )
      )
    )
  )

  /** VRF read result for each slot,
    * 3 is for [[source1]] [[source2]] [[source3]]
    */
  val vrfReadResult: Vec[Vec[UInt]] = Wire(
    Vec(
      parameter.chainingSize,
      Vec(3, UInt(parameter.datapathWidth.W))
    )
  )

  /** signal used for prohibiting slots to access VRF.
    * a slot will become inactive when:
    * 1. cross lane read/write is not finished
    * 2. lanes doesn't win mask request
    */
  val slotActive: Vec[Bool] = Wire(Vec(parameter.chainingSize, Bool()))

  /** TODO: uarch doc how to shift slot
    * the slot can slot shift when:
    */
  val slotCanShift: Vec[Bool] = Wire(Vec(parameter.chainingSize, Bool()))

  /** cross lane reading port from [[readBusPort]]
    * if [[ReadBusData.target]] matches the index of this lane, dequeue from ring
    */
  val readBusDequeue: ValidIO[ReadBusData] = Wire(Valid(new ReadBusData(parameter: LaneParameter)))

  // control signals for VFU, see [[parameter.executeUnitNum]]
  /** enqueue valid for execution unit */
  val executeEnqueueValid: Vec[UInt] = Wire(Vec(parameter.chainingSize, UInt(parameter.executeUnitNum.W)))

  /** enqueue fire signal for execution unit */
  val executeEnqueueFire: UInt = Wire(UInt(parameter.executeUnitNum.W))

  /** for most of time, dequeue is enqueue,
    * for long latency FPU(divider), fire signal is from that unit
    */
  val executeDequeueFire: UInt = Wire(UInt(parameter.executeUnitNum.W))

  /** execution result for each VFU. */
  val executeDequeueData: Vec[UInt] = Wire(Vec(parameter.executeUnitNum, UInt(parameter.datapathWidth.W)))

  /** mask format result out put in adder. */
  val adderMaskResp: Bool = Wire(Bool())

  /** vxsat for saturate. */
  val vxsat: Bool = Wire(Bool())

  /** for each slot, it occupies which VFU. */
  val instructionTypeVec: Vec[UInt] = Wire(Vec(parameter.chainingSize, UInt(parameter.executeUnitNum.W)))

  /** a instruction is finished in this lane. */
  val instructionExecuteFinished: Vec[Bool] = Wire(Vec(parameter.chainingSize, Bool()))

  /** Instructions that read across lane will have an extra set of reads if vl is not aligned. */
  val instructionCrossReadFinished: Bool = Wire(Bool())

  /** valid for requesting mask unit. */
  val maskRequestValids: Vec[Bool] = Wire(Vec(parameter.chainingSize, Bool()))

  /** request for logic instruction type. */
  val logicRequests: Vec[MaskedLogicRequest] = Wire(
    Vec(parameter.chainingSize, new MaskedLogicRequest(parameter.datapathWidth))
  )

  /** request for adder instruction type. */
  val adderRequests: Vec[LaneAdderReq] = Wire(Vec(parameter.chainingSize, new LaneAdderReq(parameter.datapathWidth)))

  /** request for shift instruction type. */
  val shiftRequests: Vec[LaneShifterReq] = Wire(
    Vec(parameter.chainingSize, new LaneShifterReq(parameter.shifterParameter))
  )

  /** request for multipler instruction type. */
  val multiplerRequests: Vec[LaneMulReq] = Wire(Vec(parameter.chainingSize, new LaneMulReq(parameter.mulParam)))

  /** request for divider instruction type. */
  val dividerRequests: Vec[LaneDivRequest] = Wire(
    Vec(parameter.chainingSize, new LaneDivRequest(parameter.datapathWidth))
  )
  val lastDivWriteIndexWire: UInt = Wire(UInt(log2Ceil(parameter.dataPathByteWidth).W))
  val divWrite:              Bool = Wire(Bool())
  val divBusy:               Bool = Wire(Bool())
  val lastDivWriteIndex:     UInt = RegEnable(lastDivWriteIndexWire, 0.U.asTypeOf(lastDivWriteIndexWire), divWrite)
  val divWriteIndex: UInt =
    Mux(divWrite, lastDivWriteIndexWire, lastDivWriteIndex)(log2Ceil(parameter.dataPathByteWidth) - 1, 0)

  /** request for other instruction type. */
  val otherRequests: Vec[OtherUnitReq] = Wire(Vec(parameter.chainingSize, Output(new OtherUnitReq(parameter))))

  val otherResponse: OtherUnitResp = Wire(Output(new OtherUnitResp(parameter.datapathWidth)))

  /** request for mask instruction type. */
  val maskRequests: Vec[LaneDataResponse] = Wire(Vec(parameter.chainingSize, Output(new LaneDataResponse(parameter))))

  /** assert when a instruction is finished in the slot. */
  val instructionFinishedVec: Vec[UInt] = Wire(Vec(parameter.chainingSize, UInt(parameter.chainingSize.W)))

  /** ready signal for enqueuing [[readBusPort]] */
  val crossLaneReadReady: Bool = Wire(Bool())

  /** ready signal for enqueuing [[writeBusPort]] */
  val crossLaneWriteReady: Bool = Wire(Bool())

  /** data for enqueuing [[readBusPort]]
    * [[crossLaneRead.valid]] indicate there is a slot try to enqueue [[readBusPort]]
    */
  val crossLaneRead: ValidIO[ReadBusData] = Wire(Valid(new ReadBusData(parameter)))

  /** data for enqueuing [[writeBusPort]]
    * [[crossLaneWrite.valid]] indicate there is a slot try to enqueue [[writeBusPort]]
    */
  val crossLaneWrite: ValidIO[WriteBusData] = Wire(Valid(new WriteBusData(parameter)))

  val vSewOrR: Bool = csrInterface.vSew.orR
  val vSew1H:  UInt = UIntToOH(csrInterface.vSew)
  // todo: - 1 in [[v]]
  val lastElementIndex: UInt = (csrInterface.vl - 1.U)(parameter.vlMaxBits - 2, 0)

  /** For an instruction, the last group is not executed by all lanes,
    * here is the last group of the instruction
    * xxxxx xxx xx -> vsew = 0
    * xxxxxx xxx x -> vsew = 1
    * xxxxxxx xxx  -> vsew = 2
    */
  val lastGroupForInstruction: UInt = Mux1H(
    vSew1H(2, 0),
    Seq(
      lastElementIndex(parameter.vlMaxBits - 2, parameter.laneNumberBits + 2),
      lastElementIndex(parameter.vlMaxBits - 2, parameter.laneNumberBits + 1),
      lastElementIndex(parameter.vlMaxBits - 2, parameter.laneNumberBits)
    )
  )

  /** Which lane the last element is in. */
  val lastLaneIndex: UInt = Mux1H(
    vSew1H(2, 0),
    Seq(
      lastElementIndex(parameter.laneNumberBits + 2 - 1, 2),
      lastElementIndex(parameter.laneNumberBits + 1 - 1, 1),
      lastElementIndex(parameter.laneNumberBits - 1, 0)
    )
  )

  /** Used to calculate the last group of mask, which will only be effective when [[isEndLane]] */
  val lastElementExecuteIndex: UInt = Mux1H(
    vSew1H(1, 0),
    Seq(
      lastElementIndex(1, 0),
      lastElementIndex(0) ## false.B
    )
  )

  /** The relative position of the last lane determines the processing of the last group. */
  val lanePositionLargerThanEndLane: Bool = laneIndex > lastLaneIndex
  val isEndLane:                     Bool = laneIndex === lastLaneIndex
  val lastGroupForLane:              UInt = lastGroupForInstruction - lanePositionLargerThanEndLane

  /** when [[InstControlRecord.executeIndex]] reaches [[slotGroupFinishedIndex]], the group in the slot is finished.
    * 00 -> 11
    * 01 -> 10
    * 10 -> 00
    *
    * TODO: 64bit
    */
  val slotGroupFinishedIndex: UInt = !csrInterface.vSew(1) ## !vSewOrR

  /** queue for cross lane writing. */
  val crossLaneWriteQueue: Queue[VRFWriteRequest] = Module(
    new Queue(
      new VRFWriteRequest(
        parameter.vrfParam.regNumBits,
        parameter.vrfOffsetBits,
        parameter.instructionIndexBits,
        parameter.datapathWidth
      ),
      parameter.crossLaneVRFWriteEscapeQueueSize
    )
  )

  slotControl.zipWithIndex.foreach {
    case (record, index) =>
      val executeFinish =
        record.state.sExecute &&
          record.state.wExecuteRes &&
          record.state.sWrite
      val crossWriteFinish: Bool = record.state.sCrossWrite0 && record.state.sCrossWrite1
      val crossReadFinish:  Bool = record.state.sSendResult0 && record.state.sSendResult1
      val schedulerFinish:  Bool = record.state.wScheduler && record.state.sScheduler
      val needMaskSource:   Bool = record.originalInformation.mask
      val readVrfRequestFinish = record.state.sReadVD && record.state.sRead1 && record.state.sRead2
      // 由于mask会作为adc的输入,关于mask的计算是所有slot都需要的
      /** for non-masked instruction, always ready,
        * for masked instruction, need to wait for mask
        */
      val maskReady: Bool = record.mask.valid || !needMaskSource

      /** 正在算的是这个lane的第多少个 element */
      val elementIndex: UInt = Mux1H(
        vSew1H(2, 0),
        Seq(
          (record.groupCounter ## record.executeIndex)(4, 0),
          (record.groupCounter ## record.executeIndex(1))(4, 0),
          record.groupCounter
        )
      )

      /** 这一组element对应的mask的值 */
      val maskBits: Bool = record.mask.bits(elementIndex(parameter.datapathWidthBits - 1, 0))
      val maskAsInput: Bool = maskBits && (record.originalInformation.decodeResult(Decoder.maskSource) ||
        record.originalInformation.decodeResult(Decoder.gather))

      /** 会跳element的mask */
      val skipEnable: Bool = record.originalInformation.mask &&
        // adc: vm = 0; madc: vm = 0 -> s0 + s1 + c, vm = 1 -> s0 + s1
        !record.originalInformation.decodeResult(Decoder.maskSource) &&
        !record.originalInformation.decodeResult(Decoder.maskLogic)

      /** !vm的时候表示这个element被mask了 */
      val masked: Bool = skipEnable && !maskBits

      // 选出下一个element的index
      val maskCorrection: UInt = Mux1H(
        Seq(skipEnable && record.mask.valid, !skipEnable),
        Seq(record.mask.bits, (-1.S(parameter.datapathWidth.W)).asUInt)
      )
      val current1H = UIntToOH(elementIndex(4, 0))
      val next1H =
        ffo((scanLeftOr(current1H) ## false.B) & maskCorrection)(parameter.datapathWidth - 1, 0)
      val writeIndex = Mux(
        record.originalInformation.decodeResult(Decoder.divider),
        divWriteIndex,
        record.executeIndex
      )
      val writeByteEnable = Mux1H(
        vSew1H(2, 0),
        Seq(
          UIntToOH(writeIndex),
          writeIndex(1) ## writeIndex(1) ## !writeIndex(1) ## !writeIndex(1),
          15.U(4.W)
        )
      )
      val writeBitEnable: UInt = FillInterleaved(8, writeByteEnable)

      /** 计算结果需要偏移的: executeIndex * 8 */
      val dataOffset: UInt = writeIndex ## 0.U(3.W)
      val nextOrR:    Bool = next1H.orR
      // nextIndex.getWidth = 5
      val nextIndex: UInt = OHToUInt(next1H)

      /**
        * 一组mask有 [[parameter.maskGroupWidth]] 个bit
        * 每0组数据执行 4, 2, 1 个 element
        */
      val lastGroupForMask = Mux1H(
        vSew1H(2, 0),
        Seq(
          record.groupCounter(log2Ceil(parameter.maskGroupWidth / 4) - 1, 0).andR,
          record.groupCounter(log2Ceil(parameter.maskGroupWidth / 2) - 1, 0).andR,
          record.groupCounter(log2Ceil(parameter.maskGroupWidth) - 1, 0).andR
        )
      )

      /** mask unit 的需要往上层传数据的控制
        * 有三种类型：
        *   1. mask destination: 这种在使用完一组mask的时候传给上层
        *   1. mask unit: 这种在每次读完数据的时候将数据传给上层
        *   1. reduce: 这种只在结束的时候给上层
        */
      val needUpdateMaskDestination: Bool = WireDefault(false.B)
      val maskTypeDestinationWriteValid =
        record.originalInformation.decodeResult(Decoder.maskDestination) && needUpdateMaskDestination
      // reduce 类型的
      val reduceType: Bool = record.originalInformation.decodeResult(Decoder.red)
      // todo: decode
      val updateReduce = (record.originalInformation.decodeResult(Decoder.red) ||
        record.originalInformation.decodeResult(Decoder.popCount)) && !record.originalInformation.loadStore
      val readOnly: Bool = record.originalInformation.decodeResult(Decoder.readOnly)
      val reduceValid = updateReduce && instructionExecuteFinished(index)
      // viota & compress & ls 需要给外边数据
      val needResponse: Bool = (record.originalInformation.loadStore || reduceValid || readOnly ||
        maskTypeDestinationWriteValid || record.originalInformation.decodeResult(Decoder.ffo)) && slotActive(index)

      // mask logic 的控制
      val lastGroupForMaskLogic: Bool = lastGroupCountForMaskLogic === record.groupCounter
      val maskLogicWillCompleted = lastGroupForMaskLogic && record.originalInformation.decodeResult(Decoder.maskLogic)
      val bordersForMaskLogic = lastGroupForMaskLogic && isLastLaneForMaskLogic &&
        dataPathMisaligned && record.originalInformation.decodeResult(Decoder.maskLogic)
      val maskCorrect = Mux(bordersForMaskLogic, lastGroupMask, fullMask)
      val notWaitFeedBack: Bool = !(readOnly || record.originalInformation.loadStore)
      val nr = record.originalInformation.decodeResult(Decoder.nr)
      // div
      val longLatency:      Bool = instructionTypeVec(index)(4)
      val maskedLogLatency: Bool = masked && longLatency
      if (index != 0) {
        // read only
        val decodeResult: DecodeBundle = record.originalInformation.decodeResult
        // TODO: use decode
        val needCrossRead = decodeResult(Decoder.firstWiden) || decodeResult(Decoder.narrow)
        // TODO: use decode
        val needCrossWrite = decodeResult(Decoder.widen)

        /** select from VFU, send to [[result]], [[crossWriteResultLSBHalf]], [[crossWriteResultMSBHalf]]. */
        val dataDequeue: UInt = Mux1H(instructionTypeVec(index), executeDequeueData)

        /** fire of [[dataDequeue]] */
        val dataDequeueFire: Bool = (instructionTypeVec(index) & executeDequeueFire).orR

        /** [[record.groupCounter]] & [[record.executeIndex]] is used as index of current data sending to VFU.
          * By default, data is not masked. due the the logic in [[next1H]], it is not masked.
          * when updating [[record.mask.bits]], the pointer is updated to the first item of mask group.
          * but this element might be masked.
          * So firstMasked is used to take care of this.
          */
        /*val firstMasked: Bool = Wire(Bool())*/
        // TODO: move this to verification module
        when(needCrossRead) {
          assert(csrInterface.vSew != 2.U)
        }
        slotActive(index) :=
          // slot should alive
          slotOccupied(index) &&
          // head should alive, if not, the slot should shift to make head alive
          slotOccupied.head &&
          // cross lane instruction should execute in the first slot
          !(needCrossRead || needCrossWrite || record.originalInformation.decodeResult(Decoder.maskOp)) &&
          // mask should ready for masked instruction
          maskReady

        // wait read result
        val readNext0 = RegNext(vrfReadRequest(index)(0).fire)
        val readNext1 = RegNext(vrfReadRequest(index)(1).fire)
        val readNext2 = RegNext(vrfReadRequest(index)(2).fire)
        // shift slot // !record.state.sExecute
        slotCanShift(index) := !((readNext0 || readNext1 || readNext2) && slotOccupied(index))

        // vs1 read
        vrfReadRequest(index)(0).valid := !record.state.sRead1 && slotActive(index)
        vrfReadRequest(index)(0).bits.offset := record.groupCounter(parameter.vrfOffsetBits - 1, 0)
        // todo: when vlmul > 0 use ## rather than +
        vrfReadRequest(index)(0).bits.vs := record.originalInformation.vs1 + record.groupCounter(
          parameter.groupNumberBits - 1,
          parameter.vrfOffsetBits
        )
        // used for hazard detection
        vrfReadRequest(index)(0).bits.instructionIndex := record.originalInformation.instructionIndex

        // vs2 read
        vrfReadRequest(index)(1).valid := !record.state.sRead2 && slotActive(index)
        vrfReadRequest(index)(1).bits.offset := record.groupCounter(parameter.vrfOffsetBits - 1, 0)
        // todo: when vlmul > 0 use ## rather than +
        // TODO: pull Mux to standalone signal
        vrfReadRequest(index)(1).bits.vs := record.originalInformation.vs2 +
          record.groupCounter(parameter.groupNumberBits - 1, parameter.vrfOffsetBits)
        vrfReadRequest(index)(1).bits.instructionIndex := record.originalInformation.instructionIndex

        // vd read
        vrfReadRequest(index)(2).valid := !record.state.sReadVD && slotActive(index)
        vrfReadRequest(index)(2).bits.offset := record.groupCounter(parameter.vrfOffsetBits - 1, 0)
        vrfReadRequest(index)(2).bits.vs := record.originalInformation.vd +
          record.groupCounter(parameter.groupNumberBits - 1, parameter.vrfOffsetBits)
        // for hazard detection
        vrfReadRequest(index)(2).bits.instructionIndex := record.originalInformation.instructionIndex

        /** all read operation is finished. */
        val readFinish = RegNext(readVrfRequestFinish) && readVrfRequestFinish

        // state machine control
        when(vrfReadRequest(index)(0).fire) {
          record.state.sRead1 := true.B
        }
        when(readNext0) {
          source1(index) := vrfReadResult(index)(0)
        }
        when(vrfReadRequest(index)(1).fire) {
          record.state.sRead2 := true.B
        }
        when(readNext1) {
          source2(index) := vrfReadResult(index)(1)
        }
        when(vrfReadRequest(index)(2).fire) {
          record.state.sReadVD := true.B
          source3(index) := vrfReadResult(index)(2)
        }
        when(readNext2) {
          source3(index) := vrfReadResult(index)(2)
        }

        /** 这一组的mask已经没有剩余了 */
        val maskNeedUpdate = !nextOrR
        val nextGroupCountMSB: UInt = Mux1H(
          vSew1H(1, 0),
          Seq(
            record.groupCounter(parameter.groupNumberBits - 1, parameter.groupNumberBits - 3),
            false.B ## record.groupCounter(parameter.groupNumberBits - 1, parameter.groupNumberBits - 2)
          )
        ) + maskNeedUpdate
        val indexInLane = nextGroupCountMSB ## nextIndex
        // csrInterface.vSew 只会取值0, 1, 2,需要特别处理移位
        val nextIntermediateVolume = (indexInLane << csrInterface.vSew).asUInt
        val nextGroupCount = nextIntermediateVolume(parameter.groupNumberBits + 1, 2)
        val nextExecuteIndex = nextIntermediateVolume(1, 0)

        /** 虽然没有计算完一组,但是这一组剩余的都被mask去掉了 */
        val maskFilterEnd = skipEnable && (nextGroupCount =/= record.groupCounter)

        /** 需要一个除vl导致的end来决定下一个的 element index 是什么 */
        val dataDepletion = writeIndex === slotGroupFinishedIndex || maskFilterEnd

        /** 这一组计算全完成了 */
        val groupEnd = dataDepletion || instructionExecuteFinished(index)

        /** 计算当前这一组的 vrf mask
          * 已知：mask mask1H executeIndex
          * sew match:
          * 0:
          * executeIndex match:
          * 0: 0001
          * 1: 0010
          * 2: 0100
          * 3: 1000
          * 1:
          * executeIndex(0) match:
          * 0: 0011
          * 1: 1100
          * 2:
          * 1111
          */
        val executeByteEnable = Mux1H(
          vSew1H(2, 0),
          Seq(
            UIntToOH(record.executeIndex),
            record.executeIndex(1) ## record.executeIndex(1) ## !record.executeIndex(1) ## !record.executeIndex(1),
            15.U(4.W)
          )
        )
        val executeBitEnable: UInt = FillInterleaved(8, executeByteEnable)

        def CollapseOperand(data: UInt, enable: Bool = true.B, sign: Bool = false.B): UInt = {
          val dataMasked: UInt = data & executeBitEnable
          val select:     UInt = Mux(enable, vSew1H(2, 0), 4.U(3.W))
          // when sew = 0
          val collapse0 = Seq.tabulate(4)(i => dataMasked(8 * i + 7, 8 * i)).reduce(_ | _)
          // when sew = 1
          val collapse1 = Seq.tabulate(2)(i => dataMasked(16 * i + 15, 16 * i)).reduce(_ | _)
          Mux1H(
            select,
            Seq(
              Fill(25, sign && collapse0(7)) ## collapse0,
              Fill(17, sign && collapse1(15)) ## collapse1,
              (sign && data(31)) ## data
            )
          )
        }

        // 处理操作数
        /**
          * src1： src1有 IXV 三种类型,只有V类型的需要移位
          */
        val finalSource1 = CollapseOperand(
          Mux(reduceType, reduceResult(index), source1(index)),
          decodeResult(Decoder.vtype) && !reduceType,
          !decodeResult(Decoder.unsigned0)
        )

        /** source2 一定是V类型的 */
        val finalSource2 = CollapseOperand(source2(index), true.B, !decodeResult(Decoder.unsigned1))

        /** source3 有两种：adc & ma, c等处理mask的时候再处理 */
        val finalSource3 = CollapseOperand(source3(index))

        val nextElementIndex = Mux1H(
          vSew1H,
          Seq(
            indexInLane(indexInLane.getWidth - 1, 2) ## laneIndex ## indexInLane(1, 0),
            indexInLane(indexInLane.getWidth - 1, 1) ## laneIndex ## indexInLane(0),
            indexInLane ## laneIndex
          )
        )
        instructionExecuteFinished(index) := nextElementIndex >= csrInterface.vl || maskLogicWillCompleted
        // 假如这个单元执行的是logic的类型的,请求应该是什么样子的
        val logicRequest = Wire(new MaskedLogicRequest(parameter.datapathWidth))
        logicRequest.src.head := finalSource2
        logicRequest.src(1) := finalSource1
        logicRequest.src(2) := maskCorrect
        logicRequest.src(3) := finalSource3
        logicRequest.opcode := decodeResult(Decoder.uop)
        // 在手动做Mux1H
        logicRequests(index) := maskAnd(
          slotOccupied(index) && decodeResult(Decoder.logic) && !decodeResult(Decoder.other),
          logicRequest
        )

        // adder 的
        val adderRequest = Wire(new LaneAdderReq(parameter.datapathWidth))
        adderRequest.src := VecInit(Seq(finalSource1, finalSource2))
        adderRequest.mask := maskAsInput
        adderRequest.opcode := decodeResult(Decoder.uop)
        adderRequest.sign := !decodeResult(Decoder.unsigned1)
        adderRequest.reverse := decodeResult(Decoder.reverse)
        adderRequest.average := decodeResult(Decoder.average)
        adderRequest.saturat := decodeResult(Decoder.saturate)
        adderRequest.maskOp := decodeResult(Decoder.maskOp)
        adderRequests(index) := maskAnd(
          slotOccupied(index) && decodeResult(Decoder.adder) && !decodeResult(Decoder.other),
          adderRequest
        )

        // shift 的
        val shiftRequest = Wire(new LaneShifterReq(parameter.shifterParameter))
        shiftRequest.src := finalSource2
        shiftRequest.shifterSize := Mux1H(
          vSew1H(2, 1),
          Seq(false.B ## finalSource1(3), finalSource1(4, 3))
        ) ## finalSource1(2, 0)
        shiftRequest.opcode := decodeResult(Decoder.uop)
        shiftRequests(index) := maskAnd(
          slotOccupied(index) && decodeResult(Decoder.shift) && !decodeResult(Decoder.other),
          shiftRequest
        )

        // mul
        val mulRequest: LaneMulReq = Wire(new LaneMulReq(parameter.mulParam))
        mulRequest.src := VecInit(Seq(finalSource1, finalSource2, finalSource3))
        mulRequest.opcode := decodeResult(Decoder.uop)
        mulRequest.saturate := decodeResult(Decoder.saturate)
        multiplerRequests(index) := maskAnd(
          slotOccupied(index) && decodeResult(Decoder.multiplier) && !decodeResult(Decoder.other),
          mulRequest
        )

        // div
        val divRequest = Wire(new LaneDivRequest(parameter.datapathWidth))
        divRequest.src := VecInit(Seq(finalSource1, finalSource2))
        divRequest.rem := decodeResult(Decoder.uop)(0)
        divRequest.sign := !decodeResult(Decoder.unsigned0)
        divRequest.index := record.executeIndex
        dividerRequests(index) := maskAnd(
          slotOccupied(index) && decodeResult(Decoder.divider) && !decodeResult(Decoder.other),
          divRequest
        )

        // other
        val otherRequest: OtherUnitReq = Wire(Output(new OtherUnitReq(parameter)))
        otherRequest.src := VecInit(Seq(finalSource1, finalSource2, finalSource3))
        otherRequest.popInit := reduceResult(index)
        otherRequest.opcode := decodeResult(Decoder.uop)(2, 0)
        otherRequest.specialOpcode := decodeResult(Decoder.specialUop)
        otherRequest.imm := record.originalInformation.vs1
        otherRequest.extendType.valid := decodeResult(Decoder.uop)(3)
        otherRequest.extendType.bits.elements.foreach { case (s, d) => d := decodeResult.elements(s) }
        otherRequest.laneIndex := laneIndex
        otherRequest.groupIndex := record.groupCounter
        otherRequest.executeIndex := record.executeIndex
        otherRequest.sign := !decodeResult(Decoder.unsigned0)
        otherRequest.mask := maskAsInput || !record.originalInformation.mask
        otherRequest.complete := record.schedulerComplete || record.selfCompleted
        otherRequest.maskType := record.originalInformation.mask
        otherRequests(index) := maskAnd(slotOccupied(index) && decodeResult(Decoder.other), otherRequest)

        // 往scheduler的执行任务compress viota
        val maskRequest: LaneDataResponse = Wire(Output(new LaneDataResponse(parameter)))
        val canSendMaskRequest = needResponse && readFinish && record.state.sExecute
        val maskValid = canSendMaskRequest && !record.state.sScheduler
        val noNeedWaitScheduler: Bool = !(canSendMaskRequest && !record.initState.sScheduler) || schedulerFinish
        // 往外边发的是原始的数据
        maskRequest.data := Mux(
          record.originalInformation.decodeResult(Decoder.maskDestination),
          maskFormatResult,
          Mux(
            updateReduce,
            reduceResult(index),
            source2(index)
          )
        )
        maskRequest.toLSU := record.originalInformation.loadStore
        maskRequest.instructionIndex := record.originalInformation.instructionIndex
        maskRequest.ffoSuccess := record.selfCompleted
        maskRequests(index) := maskAnd(slotOccupied(index) && maskValid, maskRequest)
        maskRequestValids(index) := maskValid

        when(
          laneResponseFeedback.valid && laneResponseFeedback.bits.instructionIndex === record.originalInformation.instructionIndex
        ) {
          record.state.wScheduler := true.B
        }
        when(maskValid) {
          record.state.sScheduler := true.B
        }
        instructionTypeVec(index) := record.originalInformation.instType
        executeEnqueueValid(index) := maskAnd(readFinish && !record.state.sExecute, instructionTypeVec(index))
        when((instructionTypeVec(index) & executeEnqueueFire).orR) {
          when(groupEnd) {
            record.state.sExecute := true.B
          }.otherwise {
            record.executeIndex := nextExecuteIndex
          }
        }

        // todo: 暂时先这样,处理mask的时候需要修
        val executeResult = (dataDequeue << dataOffset).asUInt(parameter.datapathWidth - 1, 0)
        val resultUpdate: UInt = (executeResult & writeBitEnable) | (result(index) & (~writeBitEnable).asUInt)
        when(dataDequeueFire) {
          when(groupEnd) {
            record.state.wExecuteRes := true.B
          }
          result(index) := resultUpdate
          record.selfCompleted := otherResponse.ffoSuccess
          when(!masked) {
            record.vrfWriteMask := record.vrfWriteMask | executeByteEnable
            when(updateReduce) {
              reduceResult(index) := dataDequeue
            }
          }
        }
        // 写rf
        vrfWriteArbiter(index).valid := record.state.wExecuteRes && !record.state.sWrite &&
        slotActive(index) && noNeedWaitScheduler
        vrfWriteArbiter(index).bits.vd := record.originalInformation.vd + record.groupCounter(
          parameter.groupNumberBits - 1,
          parameter.vrfOffsetBits
        )
        vrfWriteArbiter(index).bits.offset := record.groupCounter
        vrfWriteArbiter(index).bits.data := Mux(record.schedulerComplete, 0.U, result(index))
        vrfWriteArbiter(index).bits.last := instructionExecuteFinished(index)
        vrfWriteArbiter(index).bits.instructionIndex := record.originalInformation.instructionIndex
        vrfWriteArbiter(index).bits.mask := record.vrfWriteMask
        when(vrfWriteFire(index)) {
          record.state.sWrite := true.B
        }
        instructionFinishedVec(index) := 0.U(parameter.chainingSize.W)
        val maskUnhindered = maskRequestFireOH(index) || !maskNeedUpdate
        when((record.state.asUInt.andR && maskUnhindered) || record.instCompleted) {
          when((instructionExecuteFinished(index) && notWaitFeedBack) || record.instCompleted) {
            slotOccupied(index) := false.B
            when(slotOccupied(index)) {
              instructionFinishedVec(index) := UIntToOH(
                record.originalInformation.instructionIndex(parameter.instructionIndexBits - 2, 0)
              )
            }
          }.otherwise {
            record.state := record.initState
            record.groupCounter := nextGroupCount
            record.executeIndex := nextExecuteIndex
            record.vrfWriteMask := 0.U
            when(maskRequestFireOH(index)) {
              record.mask.valid := true.B
              record.mask.bits := maskInput
              record.maskGroupedOrR := maskGroupedOrR
            }
          }
        }
        when(
          laneResponseFeedback.bits.complete && laneResponseFeedback.bits.instructionIndex === record.originalInformation.instructionIndex
        ) {
          // 例如:别的lane找到了第一个1
          record.schedulerComplete := true.B
          when(record.initState.wExecuteRes) {
            slotOccupied(index) := false.B
          }
        }
        // mask 更换
        slotMaskRequestVec(index).valid := maskNeedUpdate
        slotMaskRequestVec(index).bits := nextGroupCountMSB
      } else {
        // read only
        val decodeResult: DecodeBundle = record.originalInformation.decodeResult
        // TODO: use decode
        val needCrossRead = decodeResult(Decoder.firstWiden) || decodeResult(Decoder.narrow)
        val crossReadVS2: Bool = needCrossRead && !decodeResult(Decoder.vwmacc)

        /** Each group needs a state machine,
          * so we will ignore the effect of mask to jump to the next group at the end of this group
          * [[needCrossRead]]: We need to read data to another lane
          * [[record.originalInformation.special]]: We need to synchronize with [[V]] every group
          */
        val alwaysNextGroup: Bool = needCrossRead ||
          record.originalInformation.special || nr

        /** select from VFU, send to [[result]], [[crossWriteResultLSBHalf]], [[crossWriteResultMSBHalf]]. */
        val dataDequeue: UInt = Mux1H(instructionTypeVec(index), executeDequeueData)

        /** fire of [[dataDequeue]] */
        val dataDequeueFire: Bool = (instructionTypeVec(index) & executeDequeueFire).orR

        // TODO: move this to verification module
        when(needCrossRead) {
          assert(csrInterface.vSew != 2.U)
        }
        slotActive(index) :=
          // slot should alive
          slotOccupied(index) &&
          // head should alive, if not, the slot should shift to make head alive
          slotOccupied.head &&
          // mask should ready for masked instruction
          maskReady

        // shift slot
        slotCanShift(index) := !(record.state.sExecute && slotOccupied(index))

        // vs1 read
        vrfReadRequest(index)(0).valid := !record.state.sRead1 && slotActive(index)
        vrfReadRequest(index)(0).bits.offset := record.groupCounter(parameter.vrfOffsetBits - 1, 0)
        vrfReadRequest(index)(0).bits.vs := Mux(
          record.originalInformation.decodeResult(Decoder.maskLogic) &&
            !record.originalInformation.decodeResult(Decoder.logic),
          // read v0 for (15. Vector Mask Instructions)
          0.U,
          // todo: when vlmul > 0 use ## rather than +
          record.originalInformation.vs1 + record.groupCounter(
            parameter.groupNumberBits - 1,
            parameter.vrfOffsetBits
          )
        )
        // used for hazard detection
        vrfReadRequest(index)(0).bits.instructionIndex := record.originalInformation.instructionIndex

        // TODO: VRF uarch doc
        //
        // example:
        //  0 ->   0  |    1 ->   0  |    2 ->   1  |    3 ->   1  |    4 ->   2  |    5 ->   2  |    6 ->   3  |    7 ->   3
        //  8 ->   4  |    9 ->   4  |   10 ->   5  |   11 ->   5  |   12 ->   6  |   13 ->   6  |   14 ->   7  |   15 ->   7
        // 16 ->   8  |   17 ->   8  |   18 ->   9  |   19 ->   9  |   20 ->  10  |   21 ->  10  |   22 ->  11  |   23 ->  11
        // 24 ->  12  |   25 ->  12  |   26 ->  13  |   27 ->  13  |   28 ->  14  |   29 ->  14  |   30 ->  15  |   31 ->  15

        // vs2 read
        vrfReadRequest(index)(1).valid := !(record.state.sRead2 && record.state.sCrossRead0) && slotActive(index)
        vrfReadRequest(index)(1).bits.offset := Mux(
          record.state.sRead2,
          record.groupCounter(parameter.vrfOffsetBits - 2, 0) ## false.B,
          record.groupCounter(parameter.vrfOffsetBits - 1, 0)
        )
        // todo: when vlmul > 0 use ## rather than +
        // TODO: pull Mux to standalone signal
        vrfReadRequest(index)(1).bits.vs := Mux(
          record.originalInformation.decodeResult(Decoder.vwmacc) && record.state.sRead2,
          record.originalInformation.vd,
          record.originalInformation.vs2
        ) + Mux(
          record.state.sRead2,
          record.groupCounter(parameter.groupNumberBits - 2, parameter.vrfOffsetBits - 1),
          record.groupCounter(parameter.groupNumberBits - 1, parameter.vrfOffsetBits)
        )
        vrfReadRequest(index)(1).bits.instructionIndex := record.originalInformation.instructionIndex

        // vd read
        vrfReadRequest(index)(2).valid := !(record.state.sReadVD && record.state.sCrossRead1) && slotActive(index)
        vrfReadRequest(index)(2).bits.offset := Mux(
          record.state.sReadVD,
          record.groupCounter(parameter.vrfOffsetBits - 2, 0) ## true.B,
          record.groupCounter(parameter.vrfOffsetBits - 1, 0)
        )
        vrfReadRequest(index)(2).bits.vs := Mux(
          record.state.sReadVD && !record.originalInformation.decodeResult(Decoder.vwmacc),
          // cross lane access use vs2
          record.originalInformation.vs2,
          // for MAC use vd
          record.originalInformation.vd
        ) +
          Mux(
            record.state.sReadVD,
            record.groupCounter(parameter.groupNumberBits - 2, parameter.vrfOffsetBits - 1),
            record.groupCounter(parameter.groupNumberBits - 1, parameter.vrfOffsetBits)
          )
        // for hazard detection
        vrfReadRequest(index)(2).bits.instructionIndex := record.originalInformation.instructionIndex

        /** all read operation is finished. */
        val readFinish =
          // wait VRF read result, +readVrfRequestFinish是因为换组后regNext会遗留上一组的值
          RegNext(readVrfRequestFinish) && readVrfRequestFinish &&
            // wait for cross lane read result
            record.state.wRead1 &&
            record.state.wRead2 &&
            record.state.sCrossRead0 &&
            record.state.sCrossRead1
        // lane 里面正常的一次状态转换,不包含与scheduler的交流
        val internalEnd: Bool = readFinish && executeFinish && crossReadFinish && crossWriteFinish
        val stateEnd:    Bool = internalEnd && schedulerFinish

        // state machine control
        when(vrfReadRequest(index)(0).fire) {
          record.state.sRead1 := true.B
        }
        when(RegNext(vrfReadRequest(index)(0).fire)) {
          // todo: datapath Mux
          source1(index) := vrfReadResult(index)(0)
        }
        when(vrfReadRequest(index)(1).fire) {
          record.state.sRead2 := true.B
          when(record.state.sRead2) {
            record.state.sCrossRead0 := true.B
          }
        }
        when(RegNext(vrfReadRequest(index)(1).fire)) {
          when(RegNext(record.state.sRead2)) {
            crossReadLSBOut := vrfReadResult(index)(1)
          }.otherwise {
            source2(index) := vrfReadResult(index)(1)
          }
        }
        when(vrfReadRequest(index)(2).fire) {
          record.state.sReadVD := true.B
          when(record.state.sReadVD) {
            record.state.sCrossRead1 := true.B
          }
        }
        when(RegNext(vrfReadRequest(index)(2).fire)) {
          when(RegNext(record.state.sReadVD)) {
            crossReadMSBOut := vrfReadResult(index)(2)
          }.otherwise {
            source3(index) := vrfReadResult(index)(2)
          }
        }

        // cross lane read
        val crossReadDataReady0 = record.state.sCrossRead0 && RegNext(record.state.sCrossRead0)
        val crossReadDataReady1 = record.state.sCrossRead1 && RegNext(record.state.sCrossRead1)
        val tryToSendHead = crossReadDataReady0 && !record.state.sSendResult0 && slotOccupied.head
        val tryToSendTail = crossReadDataReady1 && !record.state.sSendResult1 && slotOccupied.head
        crossLaneRead.bits.target := (!tryToSendHead) ## laneIndex(parameter.laneNumberBits - 1, 1)
        crossLaneRead.bits.tail := laneIndex(0)
        crossLaneRead.bits.from := laneIndex
        crossLaneRead.bits.instIndex := record.originalInformation.instructionIndex
        crossLaneRead.bits.counter := record.groupCounter
        crossLaneRead.bits.data := Mux(tryToSendHead, crossReadLSBOut, crossReadMSBOut)
        crossLaneRead.valid := tryToSendHead || tryToSendTail

        // 跨lane的写
        val sendWriteHead = record.state.sExecute && !record.state.sCrossWrite0 && slotOccupied.head
        val sendWriteTail = record.state.sExecute && !record.state.sCrossWrite1 && slotOccupied.head
        crossLaneWrite.bits.target := laneIndex(parameter.laneNumberBits - 2, 0) ## (!sendWriteHead)
        crossLaneWrite.bits.from := laneIndex
        crossLaneWrite.bits.tail := laneIndex(parameter.laneNumberBits - 1)
        crossLaneWrite.bits.instIndex := record.originalInformation.instructionIndex
        crossLaneWrite.bits.counter := record.groupCounter
        crossLaneWrite.bits.data := Mux(sendWriteHead, crossWriteResultLSBHalf, crossWriteResultMSBHalf)
        crossLaneWrite.bits.mask := Mux(sendWriteHead, crossWriteMaskLSBHalf, crossWriteMaskMSBHalf)
        crossLaneWrite.valid := sendWriteHead || sendWriteTail

        // 跨lane读写的数据接收
        when(readBusDequeue.valid) {
          assert(readBusDequeue.bits.instIndex === record.originalInformation.instructionIndex)
          when(readBusDequeue.bits.tail) {
            record.state.wRead2 := true.B
            crossReadMSBIn := readBusDequeue.bits.data
          }.otherwise {
            record.state.wRead1 := true.B
            crossReadLSBIn := readBusDequeue.bits.data
          }
        }

        // 读环发送的状态变化
        // todo: 处理发给自己的, 可以在使用的时候直接用读的寄存器, init state的时候自己纠正回来
        when(crossLaneReadReady && crossLaneRead.valid) {
          when(tryToSendHead) {
            record.state.sSendResult0 := true.B
          }.otherwise {
            record.state.sSendResult1 := true.B
          }
        }
        // 写环发送的状态变化
        when(crossLaneWriteReady && crossLaneWrite.valid) {
          record.state.sCrossWrite0 := true.B
          when(record.state.sCrossWrite0) {
            record.state.sCrossWrite1 := true.B
          }
        }

        /** 记录跨lane的写
          * sew = 2的时候不会有双倍的写,所以只需要处理sew=0和sew=1
          * sew:
          *   0:
          *     executeIndex:
          *       0: mask = 0011, head
          *       1: mask = 1100, head
          *       2: mask = 0011, tail
          *       3: mask = 1100, tail
          *   1:
          *     executeIndex:
          *       0: mask = 1111, head
          *       2: mask = 1111, tail
          */
        // dataDeq
        when(dataDequeueFire && !masked) {
          when(record.executeIndex(1)) {
            // update tail
            crossWriteResultMSBHalf :=
              Mux(
                csrInterface.vSew(0),
                dataDequeue(parameter.datapathWidth - 1, parameter.halfDatapathWidth),
                Mux(
                  record.executeIndex(0),
                  dataDequeue(parameter.halfDatapathWidth - 1, 0),
                  crossWriteResultMSBHalf(parameter.datapathWidth - 1, parameter.halfDatapathWidth)
                )
              ) ## Mux(
                !record.executeIndex(0) || csrInterface.vSew(0),
                dataDequeue(parameter.halfDatapathWidth - 1, 0),
                crossWriteResultMSBHalf(parameter.halfDatapathWidth - 1, 0)
              )
            crossWriteMaskMSBHalf :=
              (record.executeIndex(0) || csrInterface.vSew(0) || crossWriteMaskMSBHalf(1)) ##
                (!record.executeIndex(0) || csrInterface.vSew(0) || crossWriteMaskMSBHalf(0))
          }.otherwise {
            // update head
            crossWriteResultLSBHalf :=
              Mux(
                csrInterface.vSew(0),
                dataDequeue(parameter.datapathWidth - 1, parameter.halfDatapathWidth),
                Mux(
                  record.executeIndex(0),
                  dataDequeue(parameter.halfDatapathWidth - 1, 0),
                  crossWriteResultLSBHalf(parameter.datapathWidth - 1, parameter.halfDatapathWidth)
                )
              ) ## Mux(
                !record.executeIndex(0) || csrInterface.vSew(0),
                dataDequeue(parameter.halfDatapathWidth - 1, 0),
                crossWriteResultLSBHalf(parameter.halfDatapathWidth - 1, 0)
              )
            crossWriteMaskLSBHalf :=
              (record.executeIndex(0) || csrInterface.vSew(0) || crossWriteMaskLSBHalf(1)) ##
                (!record.executeIndex(0) || csrInterface.vSew(0) || crossWriteMaskLSBHalf(0))
          }

        }
        when(record.state.asUInt.andR) {
          crossWriteMaskLSBHalf := 0.U
          crossWriteMaskMSBHalf := 0.U
        }

        /** 这一组的mask已经没有剩余了 */
        val maskNeedUpdate = !nextOrR && (!alwaysNextGroup || lastGroupForMask)
        val nextGroupCountMSB: UInt = Mux1H(
          vSew1H(1, 0),
          Seq(
            record.groupCounter(parameter.groupNumberBits - 1, parameter.groupNumberBits - 3),
            false.B ## record.groupCounter(parameter.groupNumberBits - 1, parameter.groupNumberBits - 2)
          )
        ) + maskNeedUpdate
        val indexInLane = nextGroupCountMSB ## nextIndex
        // csrInterface.vSew 只会取值0, 1, 2,需要特别处理移位
        val nextIntermediateVolume = (indexInLane << csrInterface.vSew).asUInt

        /** mask 后 ffo 计算出来的下一次计算的 element 属于哪一个 group */
        val nextGroupMasked: UInt = nextIntermediateVolume(parameter.groupNumberBits + 1, 2)
        val nextGroupCount = Mux(
          alwaysNextGroup,
          record.groupCounter + 1.U,
          nextGroupMasked
        )

        /** 虽然没有计算完一组,但是这一组剩余的都被mask去掉了 */
        val maskFilterEnd = skipEnable && (nextGroupMasked =/= record.groupCounter)

        /** 这是会执行的最后一组 */
        val lastExecuteGroup: Bool = lastGroupForLane === record.groupCounter

        val lastExecuteIndex = Mux(
          lastExecuteGroup && isEndLane && !record.originalInformation.decodeResult(Decoder.maskLogic),
          lastElementExecuteIndex,
          slotGroupFinishedIndex
        )

        /**
          * 需要一个除vl导致的end来决定下一个的 element index 是什么
          * 在vl没对齐的情况下，最后一组的结束的时候的 [[record.executeIndex]] 需要修正
          */
        val groupEnd = (writeIndex === lastExecuteIndex) || maskFilterEnd
        val enqGroupEnd = (record.executeIndex === lastExecuteIndex) || maskFilterEnd

        /** 只会发生在跨lane读 */
        val waitCrossRead = lastGroupForLane < record.groupCounter && notWaitFeedBack
        val lastVRFWrite: Bool = lastGroupForLane < nextGroupCount

        val nextExecuteIndex = Mux(
          alwaysNextGroup && groupEnd,
          0.U,
          nextIntermediateVolume(1, 0)
        )

        /** 计算当前这一组的 vrf mask
          * 已知：mask mask1H executeIndex
          * sew match:
          *   0:
          *     executeIndex match:
          *       0: 0001
          *       1: 0010
          *       2: 0100
          *       3: 1000
          *   1:
          *     executeIndex(0) match:
          *       0: 0011
          *       1: 1100
          *   2:
          *     1111
          */
        val executeByteEnable = Mux1H(
          vSew1H(2, 0),
          Seq(
            UIntToOH(record.executeIndex),
            record.executeIndex(1) ## record.executeIndex(1) ## !record.executeIndex(1) ## !record.executeIndex(1),
            15.U(4.W)
          )
        )
        val executeBitEnable: UInt = FillInterleaved(8, executeByteEnable)

        def CollapseOperand(data: UInt, enable: Bool = true.B, sign: Bool = false.B): UInt = {
          val dataMasked: UInt = data & executeBitEnable
          val select:     UInt = Mux(enable, vSew1H(2, 0), 4.U(3.W))
          // when sew = 0
          val collapse0 = Seq.tabulate(4)(i => dataMasked(8 * i + 7, 8 * i)).reduce(_ | _)
          // when sew = 1
          val collapse1 = Seq.tabulate(2)(i => dataMasked(16 * i + 15, 16 * i)).reduce(_ | _)
          Mux1H(
            select,
            Seq(
              Fill(25, sign && collapse0(7)) ## collapse0,
              Fill(17, sign && collapse1(15)) ## collapse1,
              (sign && data(31)) ## data
            )
          )
        }

        // 有2 * sew 的操作数需要折叠
        def CollapseDoubleOperand(sign: Bool = false.B): UInt = {
          val doubleBitEnable = FillInterleaved(16, executeByteEnable)
          val doubleDataMasked: UInt = (crossReadMSBIn ## crossReadLSBIn) & doubleBitEnable
          val select:           UInt = vSew1H(1, 0)
          // when sew = 0
          val collapse0 = Seq.tabulate(4)(i => doubleDataMasked(16 * i + 15, 16 * i)).reduce(_ | _)
          // when sew = 1
          val collapse1 = Seq.tabulate(2)(i => doubleDataMasked(32 * i + 31, 32 * i)).reduce(_ | _)
          Mux1H(
            select,
            Seq(
              Fill(16, sign && collapse0(15)) ## collapse0,
              collapse1
            )
          )
        }
        // 处理操作数
        /**
          * src1： src1有 IXV 三种类型,只有V类型的需要移位
          */
        val finalSource1 = CollapseOperand(
          Mux(reduceType, reduceResult(index), source1(index)),
          decodeResult(Decoder.vtype) && !reduceType,
          !decodeResult(Decoder.unsigned0)
        )

        /** source2 一定是V类型的 */
        val doubleCollapse = CollapseDoubleOperand(!decodeResult(Decoder.unsigned1))
        val finalSource2 = Mux(
          crossReadVS2,
          doubleCollapse,
          CollapseOperand(source2(index), true.B, !decodeResult(Decoder.unsigned1))
        )
        val nrEnd: Bool = record.groupCounter === (record.originalInformation.vs1) ## 3.U(2.W)
        instructionExecuteFinished(index) := Mux(
          nr,
          nrEnd,
          waitCrossRead || (lastExecuteGroup && groupEnd) || maskLogicWillCompleted
        )
        instructionCrossReadFinished := waitCrossRead || readFinish

        /** source3 有两种：adc & ma, c等处理mask的时候再处理 */
        val finalSource3 = Mux(decodeResult(Decoder.vwmacc), doubleCollapse, CollapseOperand(source3(index)))
        // 假如这个单元执行的是logic的类型的,请求应该是什么样子的
        val logicRequest = Wire(new MaskedLogicRequest(parameter.datapathWidth))
        logicRequest.src.head := finalSource2
        logicRequest.src(1) := finalSource1
        logicRequest.src(2) := maskCorrect
        logicRequest.src(3) := finalSource3
        logicRequest.opcode := decodeResult(Decoder.uop)
        // 在手动做Mux1H
        logicRequests(index) := maskAnd(
          slotOccupied(index) && decodeResult(Decoder.logic) && !decodeResult(Decoder.other),
          logicRequest
        )

        // adder 的
        val adderRequest = Wire(new LaneAdderReq(parameter.datapathWidth))
        adderRequest.src := VecInit(Seq(finalSource1, finalSource2))
        adderRequest.mask := maskAsInput
        adderRequest.opcode := decodeResult(Decoder.uop)
        adderRequest.sign := !decodeResult(Decoder.unsigned1)
        adderRequest.reverse := decodeResult(Decoder.reverse)
        adderRequest.average := decodeResult(Decoder.average)
        adderRequest.saturat := decodeResult(Decoder.saturate)
        adderRequest.maskOp := decodeResult(Decoder.maskOp)
        adderRequests(index) := maskAnd(
          slotOccupied(index) && decodeResult(Decoder.adder) && !decodeResult(Decoder.other),
          adderRequest
        )

        // shift 的
        val shiftRequest = Wire(new LaneShifterReq(parameter.shifterParameter))
        shiftRequest.src := finalSource2
        // 2 * sew 有额外1bit的
        shiftRequest.shifterSize := Mux1H(
          Mux(needCrossRead, vSew1H(1, 0), vSew1H(2, 1)),
          Seq(false.B ## finalSource1(3), finalSource1(4, 3))
        ) ## finalSource1(2, 0)
        shiftRequest.opcode := decodeResult(Decoder.uop)
        shiftRequests(index) := maskAnd(
          slotOccupied(index) && decodeResult(Decoder.shift) && !decodeResult(Decoder.other),
          shiftRequest
        )

        // mul
        val mulRequest: LaneMulReq = Wire(new LaneMulReq(parameter.mulParam))
        mulRequest.src := VecInit(Seq(finalSource1, finalSource2, finalSource3))
        mulRequest.opcode := decodeResult(Decoder.uop)
        mulRequest.saturate := decodeResult(Decoder.saturate)
        multiplerRequests(index) := maskAnd(
          slotOccupied(index) && decodeResult(Decoder.multiplier) && !decodeResult(Decoder.other),
          mulRequest
        )

        // div
        val divRequest = Wire(new LaneDivRequest(parameter.datapathWidth))
        divRequest.src := VecInit(Seq(finalSource1, finalSource2))
        divRequest.rem := decodeResult(Decoder.uop)(0)
        divRequest.sign := !decodeResult(Decoder.unsigned0)
        divRequest.index := record.executeIndex
        dividerRequests(index) := maskAnd(
          slotOccupied(index) && decodeResult(Decoder.divider) && !decodeResult(Decoder.other),
          divRequest
        )

        // other
        val otherRequest: OtherUnitReq = Wire(Output(new OtherUnitReq(parameter)))
        otherRequest.src := VecInit(Seq(finalSource1, finalSource2, finalSource3))
        otherRequest.popInit := reduceResult(index)
        otherRequest.opcode := decodeResult(Decoder.uop)(2, 0)
        otherRequest.specialOpcode := decodeResult(Decoder.specialUop)
        otherRequest.imm := record.originalInformation.vs1
        otherRequest.extendType.valid := decodeResult(Decoder.uop)(3)
        otherRequest.extendType.bits.elements.foreach { case (s, d) => d := decodeResult.elements(s) }
        otherRequest.laneIndex := laneIndex
        otherRequest.groupIndex := record.groupCounter
        otherRequest.executeIndex := record.executeIndex
        otherRequest.sign := !decodeResult(Decoder.unsigned0)
        // todo: vmv.v.* decode to mv instead of merge or delete mv
        otherRequest.mask := maskAsInput || !record.originalInformation.mask
        otherRequest.complete := record.schedulerComplete || record.selfCompleted
        otherRequest.maskType := record.originalInformation.mask
        otherRequests(index) := maskAnd(slotOccupied(index) && decodeResult(Decoder.other), otherRequest)

        // 往scheduler的执行任务compress viota
        val maskRequest: LaneDataResponse = Wire(Output(new LaneDataResponse(parameter)))
        needUpdateMaskDestination := lastGroupForMask || instructionExecuteFinished(index)

        /** mask类型的指令并不是每一轮的状态机都会需要与scheduler交流
          * 只有mask destination 类型的才会有scheduler与状态机不对齐的情况
          *   我们用[[maskTypeDestinationWriteValid]]区分这种
          * 有每轮需要交换和只需要交换一次的区别(compress & red)
          */
        val canSendMaskRequest = needResponse && readFinish && record.state.sExecute
        val maskValid = canSendMaskRequest && !record.state.sScheduler
        val noNeedWaitScheduler: Bool = !(canSendMaskRequest && !record.initState.sScheduler) || schedulerFinish
        // 往外边发的是原始的数据
        maskRequest.data := Mux(
          // todo: decode
          record.originalInformation.decodeResult(Decoder.maskDestination) && !record.originalInformation.loadStore,
          maskFormatResult,
          Mux(
            updateReduce,
            reduceResult(index),
            Mux(
              record.originalInformation.decodeResult(Decoder.gather) && !record.originalInformation.loadStore,
              source1(index),
              Mux(
                record.originalInformation.decodeResult(Decoder.ffo) && !record.originalInformation.loadStore,
                ffoIndexReg,
                source2(index)
              )
            )
          )
        )
        maskRequest.toLSU := record.originalInformation.loadStore
        maskRequest.instructionIndex := record.originalInformation.instructionIndex
        maskRequest.ffoSuccess := record.selfCompleted
        maskRequests(index) := maskAnd(slotOccupied(index) && maskValid, maskRequest)
        maskRequestValids(index) := maskValid

        when(
          laneResponseFeedback.valid && laneResponseFeedback.bits.instructionIndex === record.originalInformation.instructionIndex
        ) {
          record.state.wScheduler := true.B
        }
        when(maskValid) {
          record.state.sScheduler := true.B
        }
        instructionTypeVec(index) := record.originalInformation.instType
        // 只有longLatency的才在masked的情况下不进执行单元
        executeEnqueueValid(index) := maskAnd(
          readFinish && !record.state.sExecute && !maskedLogLatency,
          instructionTypeVec(index)
        )
        // todo: 不用在lane执行的maskUnit的指令不要把sExecute初始值设0
        when((instructionTypeVec(index) & executeEnqueueFire).orR || maskedLogLatency) {
          when(enqGroupEnd) {
            record.state.sExecute := true.B
          }.otherwise {
            record.executeIndex := nextExecuteIndex
          }
        }

        // todo: 暂时先这样,处理mask的时候需要修
        val executeResult = (dataDequeue << dataOffset).asUInt(parameter.datapathWidth - 1, 0)
        val writeByteEnable = Mux1H(
          vSew1H(2, 0),
          Seq(
            UIntToOH(writeIndex),
            writeIndex(1) ## writeIndex(1) ## !writeIndex(1) ## !writeIndex(1),
            15.U(4.W)
          )
        )
        val resultUpdate: UInt = (executeResult & writeBitEnable) | (result(index) & (~writeBitEnable).asUInt)
        // 第一组div整个被跳过了的情况
        when(dataDequeueFire || (longLatency && groupEnd && record.state.sExecute && !divBusy)) {
          when(groupEnd && !(divBusy && longLatency)) {
            record.state.wExecuteRes := true.B
          }
          result(index) := resultUpdate
          record.selfCompleted := otherResponse.ffoSuccess
          when(otherResponse.ffoSuccess && !record.selfCompleted) {
            ffoIndexReg := record.groupCounter ## Mux1H(
              vSew1H,
              Seq(
                record.executeIndex ## otherResponse.data(2, 0),
                record.executeIndex(1) ## otherResponse.data(3, 0),
                otherResponse.data(4, 0)
              )
            )
          }
          // div的mask指挥影响req,不会影响回应
          when(!masked || (longLatency && divWrite)) {
            record.vrfWriteMask := record.vrfWriteMask | writeByteEnable
            when(updateReduce) {
              reduceResult(index) := dataDequeue
            }
          }
        }

        // 更新mask类型的结果
        val elementMaskFormatResult: UInt = Mux(adderMaskResp, current1H, 0.U)
        val maskFormatResultUpdate:  UInt = maskFormatResult | Mux(masked, 0.U, elementMaskFormatResult)
        when(dataDequeueFire || maskValid) {
          maskFormatResult := Mux(maskValid, 0.U, maskFormatResultUpdate)
        }

        // 写rf
        val completeWrite = Mux(record.originalInformation.mask, (~source1(index)).asUInt & source3(index), 0.U)
        vrfWriteArbiter(index).valid := record.state.wExecuteRes && !record.state.sWrite && readFinish &&
        slotActive(index) && noNeedWaitScheduler
        vrfWriteArbiter(index).bits.vd := record.originalInformation.vd + record.groupCounter(
          parameter.groupNumberBits - 1,
          parameter.vrfOffsetBits
        )
        vrfWriteArbiter(index).bits.offset := record.groupCounter
        vrfWriteArbiter(index).bits.data := Mux(
          record.schedulerComplete,
          completeWrite,
          Mux(nr, source2(index), result(index))
        )
        // todo: 是否条件有多余
        vrfWriteArbiter(index).bits.last := instructionExecuteFinished(index) || lastVRFWrite
        vrfWriteArbiter(index).bits.instructionIndex := record.originalInformation.instructionIndex
        vrfWriteArbiter(index).bits.mask := record.vrfWriteMask | Fill(4, nr)
        when(vrfWriteFire(index)) {
          record.state.sWrite := true.B
        }
        instructionFinishedVec(index) := 0.U(parameter.chainingSize.W)
        val maskUnhindered = maskRequestFireOH(index) || !maskNeedUpdate
        val stateCheck = Mux(
          waitCrossRead,
          readFinish,
          internalEnd && (!needResponse || schedulerFinish) && maskUnhindered
        )
        val crossReadReady: Bool = !needCrossRead || instructionCrossReadFinished
        when(stateCheck || record.instCompleted) {
          when((instructionExecuteFinished(index) && crossReadReady && notWaitFeedBack) || record.instCompleted) {
            slotOccupied(index) := false.B
            maskFormatResult := 0.U
            when(slotOccupied(index)) {
              instructionFinishedVec(index) := UIntToOH(
                record.originalInformation.instructionIndex(parameter.instructionIndexBits - 2, 0)
              )
            }
          }.otherwise {
            record.state := record.initState
            record.groupCounter := nextGroupCount
            record.executeIndex := nextExecuteIndex
            record.vrfWriteMask := 0.U
            when(maskRequestFireOH(index)) {
              record.mask.valid := true.B
              record.mask.bits := maskInput
              record.maskGroupedOrR := maskGroupedOrR
            }
          }
        }
        when(
          laneResponseFeedback.bits.complete && laneResponseFeedback.valid &&
            laneResponseFeedback.bits.instructionIndex === record.originalInformation.instructionIndex
        ) {
          // 例如:别的lane找到了第一个1
          record.schedulerComplete := true.B
          when(record.initState.wExecuteRes) {
            slotOccupied(index) := false.B
          }
        }
        // mask 更换
        slotMaskRequestVec(index).valid := maskNeedUpdate
        slotMaskRequestVec(index).bits := nextGroupCountMSB
      }
  }

  // 处理读环的
  {
    val readBusDataReg: ValidIO[ReadBusData] = RegInit(0.U.asTypeOf(Valid(new ReadBusData(parameter))))
    val readBusDequeueMatch = readBusPort.enq.bits.target === laneIndex &&
      readBusPort.enq.bits.counter === slotControl.head.groupCounter
    readBusDequeue.valid := readBusDequeueMatch && readBusPort.enq.valid
    readBusDequeue.bits := readBusPort.enq.bits
    // 暂时优先级策略是环上的优先
    readBusPort.enq.ready := true.B
    readBusDataReg.valid := false.B

    when(readBusPort.enq.valid) {
      when(!readBusDequeueMatch) {
        readBusDataReg.valid := true.B
        readBusDataReg.bits := readBusPort.enq.bits
      }
    }

    // 试图进环
    readBusPort.deq.valid := readBusDataReg.valid || crossLaneRead.valid
    readBusPort.deq.bits := Mux(readBusDataReg.valid, readBusDataReg.bits, crossLaneRead.bits)
    crossLaneReadReady := !readBusDataReg.valid
  }

  // 处理写环
  {
    val writeBusDataReg: ValidIO[WriteBusData] = RegInit(0.U.asTypeOf(Valid(new WriteBusData(parameter))))
    // 策略依然是环上的优先,如果queue满了继续转
    val writeBusIndexMatch = writeBusPort.enq.bits.target === laneIndex && crossLaneWriteQueue.io.enq.ready
    writeBusPort.enq.ready := true.B
    writeBusDataReg.valid := false.B
    crossLaneWriteQueue.io.enq.bits.vd := slotControl.head.originalInformation.vd + writeBusPort.enq.bits.counter(3, 1)
    crossLaneWriteQueue.io.enq.bits.offset := writeBusPort.enq.bits.counter ## writeBusPort.enq.bits.tail
    crossLaneWriteQueue.io.enq.bits.data := writeBusPort.enq.bits.data
    crossLaneWriteQueue.io.enq.bits.last := instructionExecuteFinished.head && writeBusPort.enq.bits.tail
    crossLaneWriteQueue.io.enq.bits.instructionIndex := slotControl.head.originalInformation.instructionIndex
    crossLaneWriteQueue.io.enq.bits.mask := FillInterleaved(2, writeBusPort.enq.bits.mask)
    //writeBusPort.enq.bits
    crossLaneWriteQueue.io.enq.valid := false.B

    when(writeBusPort.enq.valid) {
      when(writeBusIndexMatch) {
        crossLaneWriteQueue.io.enq.valid := true.B
      }.otherwise {
        writeBusDataReg.valid := true.B
        writeBusDataReg.bits := writeBusPort.enq.bits
      }
    }

    // 进写环
    writeBusPort.deq.valid := writeBusDataReg.valid || crossLaneWrite.valid
    writeBusPort.deq.bits := Mux(writeBusDataReg.valid, writeBusDataReg.bits, crossLaneWrite.bits)
    crossLaneWriteReady := !writeBusDataReg.valid
  }

  // VFU
  // TODO: reuse logic, adder, multiplier datapath
  {
    val logicUnit: MaskedLogic = Module(new MaskedLogic(parameter.datapathWidth))
    val adder:     LaneAdder = Module(new LaneAdder(parameter.datapathWidth))
    val shifter:   LaneShifter = Module(new LaneShifter(parameter.shifterParameter))
    val mul:       LaneMul = Module(new LaneMul(parameter.mulParam))
    val div:       LaneDiv = Module(new LaneDiv(parameter.datapathWidth))
    val otherUnit: OtherUnit = Module(new OtherUnit(parameter))

    // 连接执行单元的请求
    logicUnit.req := VecInit(logicRequests.map(_.asUInt))
      .reduce(_ | _)
      .asTypeOf(new MaskedLogicRequest(parameter.datapathWidth))
    adder.req := VecInit(adderRequests.map(_.asUInt)).reduce(_ | _).asTypeOf(new LaneAdderReq(parameter.datapathWidth))
    shifter.req := VecInit(shiftRequests.map(_.asUInt))
      .reduce(_ | _)
      .asTypeOf(new LaneShifterReq(parameter.shifterParameter))
    mul.req := VecInit(multiplerRequests.map(_.asUInt)).reduce(_ | _).asTypeOf(new LaneMulReq(parameter.mulParam))
    div.req.bits := VecInit(dividerRequests.map(_.asUInt))
      .reduce(_ | _)
      .asTypeOf(new LaneDivRequest(parameter.datapathWidth))
    otherUnit.req := VecInit(otherRequests.map(_.asUInt)).reduce(_ | _).asTypeOf(Output(new OtherUnitReq(parameter)))
    laneResponse.bits := VecInit(maskRequests.map(_.asUInt))
      .reduce(_ | _)
      .asTypeOf(Output(new LaneDataResponse(parameter)))
    laneResponse.valid := maskRequestValids.asUInt.orR
    // 执行单元的其他连接
    adder.csr.vSew := csrInterface.vSew
    adder.csr.vxrm := csrInterface.vxrm
    otherUnit.csr.vSew := csrInterface.vSew
    otherUnit.csr.vxrm := csrInterface.vxrm
    div.mask := DontCare
    div.vSew := csrInterface.vSew
    mul.csrInterface := csrInterface
    shifter.vxrm := csrInterface.vxrm
    otherResponse := otherUnit.resp
    lastDivWriteIndexWire := div.index
    divWrite := div.resp.valid
    divBusy := div.busy

    // 连接执行结果
    executeDequeueData := VecInit(
      Seq(logicUnit.resp, adder.resp.data, shifter.resp, mul.resp, div.resp.bits, otherUnit.resp.data)
    )
    executeDequeueFire := executeEnqueueFire(5) ## div.resp.valid ## executeEnqueueFire(3, 0)
    // 执行单元入口握手
    val tryToUseExecuteUnit = VecInit(executeEnqueueValid.map(_.asBools).transpose.map(VecInit(_).asUInt.orR)).asUInt
    executeEnqueueFire := tryToUseExecuteUnit & (true.B ## div.req.ready ## 15.U(4.W))
    div.req.valid := tryToUseExecuteUnit(4)
    adderMaskResp := adder.resp.singleResult
    // todo: vssra
    vxsat := adder.resp.vxsat
  }

  // 处理 rf
  {
    // 连接读口
    val readArbiter = Module(
      new Arbiter(
        new VRFReadRequest(parameter.vrfParam.regNumBits, parameter.vrfOffsetBits, parameter.instructionIndexBits),
        8
      )
    )
    // 暂时把lsu的读放在了最低优先级,有问题再改
    (vrfReadRequest(1).last +: (vrfReadRequest(2) ++ vrfReadRequest(3)) :+ vrfReadAddressChannel)
      .zip(readArbiter.io.in)
      .foreach {
        case (source, sink) =>
          sink <> source
      }
    (vrfReadRequest.head ++ vrfReadRequest(1).init :+ readArbiter.io.out).zip(vrf.read).foreach {
      case (source, sink) =>
        sink <> source
    }

    // 读的结果
    vrfReadResult.foreach(a => a.foreach(_ := vrf.readResult.last))
    (vrfReadResult.head ++ vrfReadResult(1).init).zip(vrf.readResult.init).foreach {
      case (sink, source) =>
        sink := source
    }
    vrfReadDataChannel := vrf.readResult.last

    // 写 rf
    val normalWrite = VecInit(vrfWriteArbiter.map(_.valid)).asUInt.orR
    val writeSelect = !normalWrite ## ffo(VecInit(vrfWriteArbiter.map(_.valid)).asUInt)
    val writeEnqBits = Mux1H(writeSelect, vrfWriteArbiter.map(_.bits) :+ crossLaneWriteQueue.io.deq.bits)
    vrf.write.valid := normalWrite || crossLaneWriteQueue.io.deq.valid
    vrf.write.bits := writeEnqBits
    crossLaneWriteQueue.io.deq.ready := !normalWrite && vrf.write.ready
    vrfWriteFire := Mux(vrf.write.ready, writeSelect, 0.U)

    //更新v0
    v0Update.valid := vrf.write.valid && writeEnqBits.vd === 0.U
    v0Update.bits.data := writeEnqBits.data
    v0Update.bits.offset := writeEnqBits.offset
    v0Update.bits.mask := writeEnqBits.mask
  }

  {
    // 处理mask的请求
    val maskSelectArbitrator = ffo(
      VecInit(slotMaskRequestVec.map(_.valid)).asUInt ##
        (laneRequest.valid && (laneRequest.bits.mask || laneRequest.bits.decodeResult(Decoder.maskSource)))
    )
    maskRequestFireOH := maskSelectArbitrator(parameter.chainingSize, 1)
    maskSelect := Mux1H(
      maskSelectArbitrator,
      0.U.asTypeOf(slotMaskRequestVec.head.bits) +: slotMaskRequestVec.map(_.bits)
    )
  }
  // 控制逻辑的移动
  val entranceControl: InstControlRecord = Wire(new InstControlRecord(parameter))
  val maskLogicCompleted: Bool = laneRequest.bits.decodeResult(Decoder.maskLogic) &&
    (laneIndex ## 0.U(parameter.datapathWidthBits.W) >= csrInterface.vl)
  entranceControl.originalInformation := laneRequest.bits
  entranceControl.state := laneRequest.bits.initState
  entranceControl.initState := laneRequest.bits.initState
  entranceControl.executeIndex := 0.U
  entranceControl.schedulerComplete := false.B
  entranceControl.selfCompleted := false.B
  entranceControl.instCompleted := (((laneIndex ## 0.U(2.W)) >> csrInterface.vSew).asUInt >= csrInterface.vl ||
    maskLogicCompleted) && !laneRequest.bits.decodeResult(Decoder.nr) && !laneRequest.bits.loadStore
  entranceControl.mask.valid := laneRequest.bits.mask
  entranceControl.mask.bits := maskInput
  entranceControl.maskGroupedOrR := maskGroupedOrR
  entranceControl.vrfWriteMask := 0.U
  // todo: vStart(2,0) > lane index
  entranceControl.groupCounter := (csrInterface.vStart >> 3).asUInt
  val src1IsSInt: Bool = !laneRequest.bits.decodeResult(Decoder.unsigned0)
  // todo: spec 10.1: imm 默认是 sign-extend,但是有特殊情况
  val immSignExtend: UInt = Fill(16, laneRequest.bits.vs1(4) && (vSew1H(2) || src1IsSInt)) ##
    Fill(8, laneRequest.bits.vs1(4) && (vSew1H(1) || vSew1H(2) || src1IsSInt)) ##
    Fill(3, laneRequest.bits.vs1(4)) ## laneRequest.bits.vs1
  val vs1entrance: UInt =
    Mux(
      laneRequest.bits.decodeResult(Decoder.vtype),
      0.U,
      Mux(
        laneRequest.bits.decodeResult(Decoder.xtype) || laneRequest.bits.decodeResult(Decoder.gather),
        laneRequest.bits.readFromScalar,
        immSignExtend
      )
    )
  val entranceInstType: UInt = laneRequest.bits.instType
  // todo: 修改v0的和使用v0作为mask的指令需要产生冲突
  val typeReady: Bool = VecInit(
    instructionTypeVec.zip(slotOccupied).map { case (t, v) => (t =/= entranceInstType) || !v }
  ).asUInt.andR
  val validRegulate: Bool = laneRequest.valid && typeReady
  val shiftReady = slotCanShift.asUInt.andR && !laneResponseFeedback.valid
  laneRequest.ready := !slotOccupied.head && typeReady && vrf.instWriteReport.ready && shiftReady
  vrf.instWriteReport.valid := (laneRequest.fire || (!laneRequest.bits.store && laneRequest.bits.loadStore)) && !entranceControl.instCompleted
  when(!slotOccupied.head && (slotOccupied.asUInt.orR || validRegulate) && shiftReady) {
    slotOccupied := VecInit(slotOccupied.tail :+ validRegulate)
    source1 := VecInit(source1.tail :+ vs1entrance)
    slotControl := VecInit(slotControl.tail :+ entranceControl)
    result := VecInit(result.tail :+ 0.U(parameter.datapathWidth.W))
    reduceResult := VecInit(reduceResult.tail :+ 0.U(parameter.datapathWidth.W))
    source2 := VecInit(source2.tail :+ 0.U(parameter.datapathWidth.W))
    source3 := VecInit(source3.tail :+ 0.U(parameter.datapathWidth.W))
    crossWriteMaskLSBHalf := 0.U
    crossWriteMaskMSBHalf := 0.U
  }
  // 试图让vrf记录这一条指令的信息,拒绝了说明有还没解决的冲突
  vrf.flush := maskUnitFlushVrf
  vrf.instWriteReport.bits.instIndex := laneRequest.bits.instructionIndex
  vrf.instWriteReport.bits.offset := 0.U //todo
  vrf.instWriteReport.bits.vdOffset := 0.U
  vrf.instWriteReport.bits.vd.bits := laneRequest.bits.vd
  vrf.instWriteReport.bits.vd.valid := !laneRequest.bits.initState.sWrite || (laneRequest.bits.loadStore && !laneRequest.bits.store)
  vrf.instWriteReport.bits.vs2 := laneRequest.bits.vs2
  vrf.instWriteReport.bits.vs1.bits := laneRequest.bits.vs1
  vrf.instWriteReport.bits.vs1.valid := laneRequest.bits.decodeResult(Decoder.vtype)
  // TODO: move ma to [[V]]
  vrf.instWriteReport.bits.ma := laneRequest.bits.ma
  // 暂时认为ld都是无序写寄存器的
  vrf.instWriteReport.bits.unOrderWrite := (laneRequest.bits.loadStore && !laneRequest.bits.store) || laneRequest.bits
    .decodeResult(Decoder.other)
  vrf.instWriteReport.bits.seg.valid := laneRequest.bits.loadStore && laneRequest.bits.segment.orR
  vrf.instWriteReport.bits.seg.bits := laneRequest.bits.segment
  vrf.instWriteReport.bits.eew := laneRequest.bits.loadStoreEEW
  vrf.instWriteReport.bits.ls := laneRequest.bits.loadStore
  vrf.instWriteReport.bits.st := laneRequest.bits.store
  vrf.instWriteReport.bits.narrow := laneRequest.bits.decodeResult(Decoder.narrow)
  vrf.instWriteReport.bits.widen := laneRequest.bits.decodeResult(Decoder.widen)
  vrf.instWriteReport.bits.stFinish := false.B
  vrf.csrInterface := csrInterface
  vrf.lsuLastReport := lsuLastReport
  vrf.bufferClear := lsuVRFWriteBufferClear
  instructionFinished := instructionFinishedVec.reduce(_ | _)
}
