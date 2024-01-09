import chisel3._
import fpgatidbits.PlatformWrapper._
import fpgatidbits.TidbitsMakeUtils

import java.nio.file.{Files, Paths, StandardCopyOption}


object MainObj {
  def main(args: Array[String]): Unit = {
    val accInst = {p => new GrayScale(p)}
    val targetDir = "build"
    val chiselArgs = Array("--target-dir", targetDir)

    TidbitsMakeUtils.tidbitsRootPath=Paths.get("fpga-tidbits").toString

    if (args.length == 1) {
      if (args(0).equals("ZedBoard")) {
        (new chisel3.stage.ChiselStage).emitVerilog(new ZedBoardWrapper(accInst, targetDir), chiselArgs)
      } else {
        println(s"Error unrecognized option ${args(1)}")
      }
    } else {
      (new chisel3.stage.ChiselStage).emitVerilog(new VerilatedTesterWrapper(accInst, targetDir), chiselArgs)
    }

    // copy test application
    Files.copy(Paths.get("src/main/resources/main.cpp"), Paths.get(s"$targetDir/main.cpp"), StandardCopyOption.REPLACE_EXISTING)
    Files.copy(Paths.get("src/main/resources/include.mk"), Paths.get(s"$targetDir/include.mk"), StandardCopyOption.REPLACE_EXISTING)
  }
}