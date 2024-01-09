import chisel3._
import chisel3.util.{Decoupled, DecoupledIO}
import fpgatidbits.PlatformWrapper._
import fpgatidbits.dma._

class GrayScaleIO(p: PlatformWrapperParams) extends GenericAcceleratorIF(1, p) {
  val start = Input(Bool())
  val finished = Output(Bool())
  val rgbAddr = Input(UInt(64.W))
  val rgbSize = Input(UInt(32.W))
  val grayAddr = Input(UInt(64.W))
  val graySize = Input(UInt(32.W))
  val cycles = Output(UInt(32.W))
}

class GrayScale(p: PlatformWrapperParams) extends GenericAccelerator(p) {
  val numMemPorts = 1
  val io = IO(new GrayScaleIO(p))
  io.signature := makeDefaultSignature()

  val rdP = new StreamReaderParams(
    streamWidth = 24, fifoElems = 8, mem = p.toMemReqParams(),
    maxBeats = 1, chanID = 0, disableThrottle = true
  )
  val wrP = new StreamWriterParams(
    streamWidth = 8, mem=p.toMemReqParams(), chanID = 0, maxBeats = 1
  )

  val reader = Module(new StreamReader(rdP)).io
  val writer = Module(new StreamWriter(wrP)).io

  // .. Connect DMAs to IO and mem-ports
  reader.start := io.start
  reader.baseAddr := io.rgbAddr
  reader.byteCount := io.rgbSize
  reader.req <> io.memPort(0).memRdReq
  io.memPort(0).memRdRsp <> reader.rsp

  writer.start := io.start
  writer.baseAddr := io.grayAddr
  writer.byteCount := io.graySize
  writer.req <> io.memPort(0).memWrReq
  writer.wdat <> io.memPort(0).memWrDat
  writer.rsp <> io.memPort(0).memWrRsp

  // ... Connect GrayFilter to memory streams
  val grayFilter = Module(new GrayScaleFilter)

  grayFilter.rgbIn.valid := reader.out.valid
  grayFilter.rgbIn.bits := reader.out.bits.asTypeOf(new Colour())
  reader.out.ready := grayFilter.rgbIn.ready
  writer.in.valid := grayFilter.grayOut.valid
  writer.in.bits := grayFilter.grayOut.bits
  grayFilter.grayOut.ready := writer.in.ready
  reader.doInit := false.B
  reader.initCount := 0.U

  io.finished := writer.finished

  val regCycleCount = RegInit(0.U(32.W))
  io.cycles := regCycleCount
  when(!io.start) {regCycleCount := 0.U}
    .elsewhen(io.start & !io.finished) {regCycleCount := regCycleCount + 1.U}
}

class Colour extends Bundle {
  val r = UInt(8.W)
  val g = UInt(8.W)
  val b = UInt(8.W)
}

class GrayScaleFilter extends Module {
  val rgbIn = IO(Flipped(Decoupled(new Colour)))
  val grayOut = IO(Decoupled(UInt(8.W)))

  val s1_valid = RegInit(false.B)
  val s1_r1Shifted = RegInit(0.U(8.W))
  val s1_r2Shifted = RegInit(0.U(8.W))
  val s1_g1Shifted = RegInit(0.U(8.W))
  val s1_g2Shifted = RegInit(0.U(8.W))
  val s1_b1Shifted = RegInit(0.U(8.W))
  val s1_b2Shifted = RegInit(0.U(8.W))

  val s2_valid = RegInit(false.B)
  val s2_gray = RegInit(0.U(8.W))

  rgbIn.ready := !s2_valid || grayOut.fire
  grayOut.valid := s2_valid
  grayOut.bits := s2_gray

  s1_valid := rgbIn.fire

  when(rgbIn.fire) {
    // Stage 1
    val rgb = rgbIn.bits
    val (r,g,b) = (rgb.r, rgb.g, rgb.b)
    s1_r1Shifted := (r >> 2).asUInt
    s1_r2Shifted := (r >> 5).asUInt
    s1_g1Shifted := (g >> 1).asUInt
    s1_g2Shifted := (g >> 4).asUInt
    s1_b1Shifted := (b >> 4).asUInt
    s1_b2Shifted := (b >> 5).asUInt

    // Stage 2
    s2_valid := s1_valid
    s2_gray := s1_r1Shifted + s1_r2Shifted + s1_g1Shifted + s1_g2Shifted + s1_b1Shifted + s1_b2Shifted
  }
}