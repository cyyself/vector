package v

import chisel3._
import chisel3.experimental.{SerializableModule, SerializableModuleParameter}
import chisel3.util._
import division.srt.SRT
import v.{BoolField, Decoder, VFUModule, VFUParameter}
import float._
import sqrt.SquareRoot

object LaneDivFPParam {
  implicit def rw: upickle.default.ReadWriter[LaneDivFPParam] = upickle.default.macroRW
}
case class LaneDivFPParam(datapathWidth: Int) extends VFUParameter with SerializableModuleParameter {
  val decodeField: BoolField = Decoder.divider
  val inputBundle  = new LaneDivFPRequest(datapathWidth)
  val outputBundle = new LaneDivFPResponse(datapathWidth)
}

class LaneDivFPRequest(datapathWidth: Int) extends Bundle {
  val src:  Vec[UInt] = Vec(2, UInt(datapathWidth.W))
  val opcode = UInt(4.W)
  val sign: Bool = Bool()
  // execute index in group
  val executeIndex: UInt = UInt(2.W)
  // csr
  // val vSew: UInt = UInt(2.W)
}

class LaneDivFPResponse(datapathWidth: Int) extends Bundle {
  val data: UInt = UInt(datapathWidth.W)
  val exceptionFlags = UInt(5.W)
  val executeIndex: UInt = UInt(2.W)
  val busy: Bool = Bool()
}

class LaneDivFP(val parameter: LaneDivFPParam) extends VFUModule(parameter) with SerializableModule[LaneDivFPParam] {
  val response: LaneDivFPResponse = Wire(new LaneDivFPResponse(parameter.datapathWidth))
  val request: LaneDivFPRequest = connectIO(response).asTypeOf(parameter.inputBundle)

  val uop = request.opcode

  val integerEn = uop(3)===0.U
  val isRem = uop==="b0001".U
  val fractEn = !integerEn
  val rdiv = (uop === "b1010".U)
  val sqrt = (uop === "b1001".U)

  val divIn0 = Mux(rdiv, request.src(0), request.src(1))
  val divIn1 = Mux(rdiv, request.src(1), request.src(0))

  val wrapper = Module(new SRTFPWrapper(8,24))
  wrapper.input.bits.a       := Mux(fractEn, divIn0.asSInt, request.src(1).asSInt)
  wrapper.input.bits.b       := Mux(fractEn, divIn1.asSInt, request.src(0).asSInt)
  wrapper.input.bits.signIn  := request.sign
  wrapper.input.bits.fractEn := fractEn
  wrapper.input.bits.sqrt    := sqrt
  wrapper.input.bits.rem     := isRem
  wrapper.input.valid        := requestIO.valid


  val requestFire: Bool = requestIO.fire
  val indexReg: UInt = RegEnable(request.executeIndex, 0.U, requestFire)
  response.busy := RegEnable(requestFire, false.B, requestFire ^ responseIO.valid)

  response.executeIndex   := indexReg
  requestIO.ready         := wrapper.input.ready
  responseIO.valid        := wrapper.output.valid
  response.data           := wrapper.output.bits.result
  response.exceptionFlags := wrapper.output.bits.exceptionFlags
}

/** 32-bits Divider for signed and unsigned division based on SRT16 with CSA
  *
  * Input:
  * dividend and divisor
  * signIn: true for signed input
  *
  * Component:
  * {{{
  * divided by zero detection
  * bigger divisor detection
  * SRT16 initial process logic containing a leading zero counter
  * SRT16 recurrence module imported from dependencies/arithmetic
  * SRT16 post-process logic
  * }}}
  */
class SRTFPWrapper(expWidth: Int, sigWidth: Int) extends Module {
  class SRTIn extends Bundle {
    val a = SInt(32.W)
    val b = SInt(32.W)
    val signIn = Bool()
    val fractEn = Bool()
    val sqrt = Bool()
    val rem = Bool()
  }
  class SRTOut extends Bundle {
    val reminder = UInt(32.W)
    val quotient = UInt(32.W)
    val result = UInt(32.W)
    val exceptionFlags = UInt(5.W)
  }
  val input = IO(Flipped(DecoupledIO(new SRTIn)))
  val output = IO(ValidIO(new SRTOut))

  val fpWidth = expWidth + sigWidth
  val calWidth = 28

  val fractEn = input.bits.fractEn
  val sqrtEn  = input.bits.sqrt
  val fractEnReg = RegEnable(fractEn, false.B, input.fire)
  val opSqrtReg  = RegEnable(input.bits.sqrt, input.fire)
  val remReg     = RegEnable(input.bits.rem, input.fire)

  val rawA_S = rawFloatFromFN(expWidth, sigWidth, input.bits.a)
  val rawB_S = rawFloatFromFN(expWidth, sigWidth, input.bits.b)

  /** Exceptions */
  val notSigNaNIn_invalidExc_S_div =
    (rawA_S.isZero && rawB_S.isZero) || (rawA_S.isInf && rawB_S.isInf)
  val notSigNaNIn_invalidExc_S_sqrt =
    !rawA_S.isNaN && !rawA_S.isZero && rawA_S.sign
  val majorExc_S =
    Mux(input.bits.sqrt,
      isSigNaNRawFloat(rawA_S) || notSigNaNIn_invalidExc_S_sqrt,
      isSigNaNRawFloat(rawA_S) || isSigNaNRawFloat(rawB_S) ||
        notSigNaNIn_invalidExc_S_div ||
        (!rawA_S.isNaN && !rawA_S.isInf && rawB_S.isZero)
    )
  val isNaN_S =
    Mux(input.bits.sqrt,
      rawA_S.isNaN || notSigNaNIn_invalidExc_S_sqrt,
      rawA_S.isNaN || rawB_S.isNaN || notSigNaNIn_invalidExc_S_div
    )
  val isInf_S =  Mux(input.bits.sqrt, rawA_S.isInf, rawA_S.isInf || rawB_S.isZero)
  val isZero_S = Mux(input.bits.sqrt, rawA_S.isZero, rawA_S.isZero || rawB_S.isInf)

  val majorExc_Z = RegEnable(majorExc_S, false.B, input.fire)
  val isNaN_Z    = RegEnable(isNaN_S, false.B, input.fire)
  val isInf_Z    = RegEnable(isInf_S, false.B, input.fire)
  val isZero_Z   = RegEnable(isZero_S, false.B, input.fire)

  val invalidExec = majorExc_Z && isNaN_Z
  val infinitExec = majorExc_Z && !isNaN_Z

  val specialCaseA_S = rawA_S.isNaN || rawA_S.isInf || rawA_S.isZero
  val specialCaseB_S = rawB_S.isNaN || rawB_S.isInf || rawB_S.isZero
  val normalCase_S_div = !specialCaseA_S && !specialCaseB_S
  val normalCase_S_sqrt = !specialCaseA_S && !rawA_S.sign
  val normalCase_S = Mux(input.bits.sqrt, normalCase_S_sqrt, normalCase_S_div)
  val specialCase_S = !normalCase_S

  val fastValid = RegInit(false.B)
  fastValid := specialCase_S && input.fire && fractEn

  // needNorm for div
  val needNormNext = input.bits.b(sigWidth - 2, 0) > input.bits.a(sigWidth - 2, 0)
  val needNorm = RegEnable(needNormNext, input.fire)

  // sign
  val signNext = Mux(input.bits.sqrt, false.B, rawA_S.sign ^ rawB_S.sign)
  val signReg = RegEnable(signNext, input.fire)

  // sqrt input
  val adjustedExp = Cat(rawA_S.sExp(expWidth - 1), rawA_S.sExp(expWidth - 1, 0))
  val sqrtExpIsEven = input.bits.a(sigWidth - 1)
  val sqrtFractIn = Mux(sqrtExpIsEven, Cat("b0".U(1.W), rawA_S.sig(sigWidth - 1, 0), 0.U(1.W)),
    Cat(rawA_S.sig(sigWidth - 1, 0), 0.U(2.W)))

  val SqrtModule = Module(new SquareRoot(2, 2, sigWidth + 2, sigWidth + 2))
  SqrtModule.input.bits.operand := sqrtFractIn
  SqrtModule.input.valid := input.valid && input.bits.sqrt && normalCase_S_sqrt


  // div FP input
  val fractDividendIn = Wire(UInt((fpWidth).W))
  val fractDivisorIn  = Wire(UInt((fpWidth).W))
  fractDividendIn := Cat(1.U(1.W), rawA_S.sig(sigWidth - 2, 0), 0.U(expWidth.W))
  fractDivisorIn  := Cat(1.U(1.W), rawB_S.sig(sigWidth - 2, 0), 0.U(expWidth.W))


//-----------------------Integer----------------------
  val abs = Module(new Abs(32))
  abs.io.aIn := input.bits.a
  abs.io.bIn := input.bits.b
  abs.io.signIn := input.bits.signIn
  val negative = abs.io.aSign ^ abs.io.bSign

  val divModule: SRT = Module(new SRT(32, 32, 32, radixLog2 = 4))

  /** divided by zero detection */
  val divideZero = (input.bits.b === 0.S)

  /** bigger divisor detection */
  val dividend = Wire(UInt(33.W))
  val divisor  = Wire(UInt(33.W))
  val gap      = Wire(UInt(34.W))
  val biggerdivisor = Wire(Bool())
  dividend := abs.io.aOut
  divisor  := abs.io.bOut
  gap := divisor +& (-dividend)
  biggerdivisor := gap(33) && !(gap(32, 0).orR === false.B)

  // bypass
  val bypassSRT = (divideZero || biggerdivisor) && input.fire && !fractEn

  /** SRT16 initial process logic containing a leading zero counter */
  // extend one bit for calculation
  val zeroHeadDividend = Wire(UInt(6.W))
  val zeroHeadDivisor  = Wire(UInt(6.W))
  zeroHeadDividend := float.countLeadingZeros(abs.io.aOut)
  zeroHeadDivisor  := float.countLeadingZeros(abs.io.bOut)
  // sub = zeroHeadDivider - zeroHeadDividend
  val sub = Wire(UInt(6.W))
  sub := (-zeroHeadDividend) +& zeroHeadDivisor
  // needComputerWidth: Int = zeroHeadDivider - zeroHeadDividend + 2
  val needComputerWidth = Wire(UInt(7.W))
  needComputerWidth := sub +& 2.U
  // guardWidth = 4 - needComputerWidth % 4(except needComputerWidth mod4 = 0 => 0 )
  val guardSele = UIntToOH(needComputerWidth(1,0))
  val guardWidth = Mux1H(Seq(
    guardSele(0) -> 0.U(2.W),
    guardSele(1) -> 3.U(2.W),
    guardSele(2) -> 2.U(2.W),
    guardSele(3) -> 1.U(2.W),
  ))
  // counter: Int = (needComputerWidth + guardWidth) / radixLog2
  val counter = ((needComputerWidth +& guardWidth) >> 2).asUInt

  val leftShiftWidthDividend = Wire(UInt(6.W))
  val leftShiftWidthDivisor  = Wire(UInt(6.W))
  leftShiftWidthDividend := zeroHeadDividend +& -Cat(0.U(4.W), guardWidth) + 3.U
  leftShiftWidthDivisor := zeroHeadDivisor(4, 0)

  // control signals used in SRT post-process
  val negativeSRT        = RegEnable(negative,        divModule.input.fire)
  val zeroHeadDivisorSRT = RegEnable(zeroHeadDivisor, divModule.input.fire)
  val dividendSignSRT    = RegEnable(abs.io.aSign,    divModule.input.fire)

  // keep for one cycle
  val divideZeroReg    = RegEnable(divideZero, false.B, input.fire)
  val biggerdivisorReg = RegEnable(biggerdivisor, false.B, input.fire)
  val bypassSRTReg     = RegNext(bypassSRT, false.B)
  val dividendInputReg = RegEnable(input.bits.a.asUInt, 0.U(32.W), input.fire)

  // SRT16 recurrence module input
  divModule.input.bits.dividend := Mux(fractEn,fractDividendIn, abs.io.aOut << leftShiftWidthDividend)
  divModule.input.bits.divider  := Mux(fractEn, fractDivisorIn, abs.io.bOut << leftShiftWidthDivisor)
  divModule.input.bits.counter  := Mux(fractEn, 8.U, counter)

  // if dividezero or biggerdivisor, bypass SRT
  divModule.input.valid := input.valid && !(bypassSRT && !fractEn) && !( !normalCase_S_div && fractEn) && !sqrtEn
  input.ready := divModule.input.ready

  // calculate quotient and remainder in ABS
  val quotientAbs  = Wire(UInt(32.W))
  val remainderAbs = Wire(UInt(32.W))
  quotientAbs  := divModule.output.bits.quotient
  remainderAbs := divModule.output.bits.reminder >> zeroHeadDivisorSRT(4, 0)

  val intQuotient  = Wire(UInt(32.W))
  val intRemainder = Wire(UInt(32.W))
  val intResult    = Wire(UInt(32.W))

  /** divInteger result collect
    *
    * when divisor equals to zero, the quotient has all bits set and the remainder equals the dividend
    */
  intQuotient := Mux(
    divideZeroReg,
    "hffffffff".U(32.W),
    Mux(
      biggerdivisorReg,
      0.U,
      Mux(negativeSRT, -quotientAbs, quotientAbs)
    )
  )
  intRemainder := Mux(
    divideZeroReg || biggerdivisorReg,
    dividendInputReg,
    Mux(dividendSignSRT, -remainderAbs, remainderAbs)
  )

  intResult := Mux(remReg, intRemainder, intQuotient)

  // -------------------- FP result collect -----------------------------------------
  val sigToRound_sqrt = SqrtModule.output.bits.result(24, 2)
  val rbits_sqrt = SqrtModule.output.bits.result(1) ## (!SqrtModule.output.bits.zeroRemainder || SqrtModule.output.bits.result(0))

  val sigToRound_div = Mux(needNorm, divModule.output.bits.quotient(calWidth - 3, calWidth - sigWidth - 1),
    divModule.output.bits.quotient(calWidth - 2, calWidth - sigWidth))
  val rbits_div = Mux(needNorm, divModule.output.bits.quotient(calWidth - sigWidth - 2) ## 1.U(1.W),
    divModule.output.bits.quotient(calWidth - sigWidth - 1) ## 1.U(1.W))


  // collect sig and round bits result
  val sigToRound   = Mux(opSqrtReg, sigToRound_sqrt, sigToRound_div)
  val rbitsToRound = Mux(opSqrtReg, rbits_sqrt, rbits_div)

  // exp logic
  val expStoreNext = Wire(UInt(expWidth.W))
  val expToRound   = Wire(UInt(expWidth.W))
  expStoreNext := Mux(input.bits.sqrt,
    Cat(rawA_S.sExp(expWidth - 1), rawA_S.sExp(expWidth - 1, 0))(expWidth, 1),
    input.bits.a(fpWidth - 1, sigWidth - 1) - input.bits.b(fpWidth - 1, sigWidth - 1))
  val expStore = RegEnable(expStoreNext, 0.U(expWidth.W), input.fire)
  expToRound := Mux(opSqrtReg, expStore, expStore - needNorm)

  val roundresult = RoundingUnit(
    signReg,
    expToRound,
    sigToRound,
    rbitsToRound,
    consts.round_near_even,
    invalidExec,
    infinitExec,
    isNaN_Z,
    isInf_Z,
    isZero_Z)


  output.valid := divModule.output.valid | bypassSRTReg | SqrtModule.output.valid | fastValid

  output.bits.quotient := intQuotient
  output.bits.reminder := intRemainder
  output.bits.result   := Mux(fractEnReg, roundresult(0), intResult)
  output.bits.exceptionFlags := roundresult(1)
}

class Abs(n: Int) extends Module {
  val io = IO(new Bundle() {
    val aIn = Input(SInt(n.W))
    val bIn = Input(SInt(n.W))
    val signIn = Input(Bool())
    val aOut = Output(UInt(n.W))
    val bOut = Output(UInt(n.W))
    val aSign = Output(Bool())
    val bSign = Output(Bool())
  })
  val a = Wire(SInt(n.W))
  val b = Wire(SInt(n.W))
  val aSign = io.aIn(n - 1)
  val bSign = io.bIn(n - 1)
  a := io.aIn
  b := io.bIn
  io.aOut := Mux(io.signIn && aSign, -a, a).asUInt
  io.bOut := Mux(io.signIn && bSign, -b, b).asUInt
  io.aSign := io.signIn && aSign
  io.bSign := io.signIn && bSign
}
