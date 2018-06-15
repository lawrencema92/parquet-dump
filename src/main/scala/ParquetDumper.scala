package com.tune

import java.io.{File, FileInputStream, DataInputStream, BufferedOutputStream, BufferedInputStream}
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import com.fasterxml.jackson.databind.ObjectMapper
import scala.collection.JavaConverters._

object ParquetDumper {
  def main(args: Array[String]) {
    org.apache.log4j.LogManager.getRootLogger.setLevel(org.apache.log4j.Level.ERROR)
    //org.apache.log4j.LogManager.getCurrentLoggers.asScala.foreach(_.setLevel(org.apache.log4j.Level.ERROR))
    org.apache.log4j.LogManager.getLogger("org").setLevel(org.apache.log4j.Level.ERROR)
    org.apache.log4j.LogManager.getLogger("akka").setLevel(org.apache.log4j.Level.ERROR)
    val fileNames = parquetToFiles()
    fileNames.grouped(20).toList.foreach(files => {
      val sess = org.apache.spark.sql.SparkSession.builder
        .master("local[*]")
        .appName("Parquet-Dumper")
        .config("spark.ui.enabled", "false")
        .getOrCreate
      import sess.implicits._
      sess.sparkContext.hadoopConfiguration.set("fs.hdfs.impl", classOf[org.apache.hadoop.hdfs.DistributedFileSystem].getName)
      sess.sparkContext.hadoopConfiguration.set("fs.file.impl", classOf[org.apache.hadoop.fs.LocalFileSystem].getName());
      sess.read.parquet(files: _*)
      .map(row => {
        new ObjectMapper().writeValueAsString(row.getValuesMap(row.schema.fieldNames).asJava)
      })
      .collect
      .foreach(println)
    })
    //val out = (new PrettyPrintWriter.Builder(System.out))
    //  .withAutoColumn()
    //  .withWhitespaceHandler(WhiteSpaceHandler.ELIMINATE_NEWLINES)
    //  .withColumnPadding(1)
    //  .withMaxBufferedLines(1000000)
    //  .withFlushOnTab()


    //fileNames.foreach(fn => {
    //  val conf = new Configuration
    //  conf.set("fs.file.impl", classOf[org.apache.hadoop.fs.LocalFileSystem].getName());
    //  val metaData = ParquetFileReader.readFooter(conf, new Path(fn), NO_FILTER)
    //  val schema = metaData.getFileMetaData().getSchema()
    //  DumpCommand.dump(out.build, metaData, schema, new Path(fn), true, true, null)
    //})
  }

  private def parquetToFiles() : scala.collection.mutable.Seq[String] = {
    val stdin = new DataInputStream(new BufferedInputStream(new FileInputStream(new File("/dev/stdin"))))

    var currentFile : java.io.File = java.io.File.createTempFile("parquet-dumper", ".parquet")
    var currentStream : java.io.DataOutputStream = new java.io.DataOutputStream( new java.io.BufferedOutputStream( new java.io.FileOutputStream(currentFile)))
    var atStart = true
    var fileNames = scala.collection.mutable.ListBuffer[String]()
    var numParOnes = 1
    var numBytes = 0
    var sawParquetMrVersion = false
    val PAR1_BEG = Vector[java.lang.Byte](
      java.lang.Byte.parseByte("80"),
      java.lang.Byte.parseByte("65"),
      java.lang.Byte.parseByte("82"),
      java.lang.Byte.parseByte("49")
    )
    val PAR1_END = Vector[java.lang.Byte](
      java.lang.Byte.parseByte("0"),
      java.lang.Byte.parseByte("0"),
      java.lang.Byte.parseByte("80"),
      java.lang.Byte.parseByte("65"),
      java.lang.Byte.parseByte("82"),
      java.lang.Byte.parseByte("49")
    )

    var PARQUET_MR_VERSION = Vector[java.lang.Byte](
      java.lang.Byte.parseByte("112"),
      java.lang.Byte.parseByte("97"),
      java.lang.Byte.parseByte("114"),
      java.lang.Byte.parseByte("113"),
      java.lang.Byte.parseByte("117"),
      java.lang.Byte.parseByte("101"),
      java.lang.Byte.parseByte("116"),
      java.lang.Byte.parseByte("45"),
      java.lang.Byte.parseByte("109"),
      java.lang.Byte.parseByte("114"),
      java.lang.Byte.parseByte("32"),
      java.lang.Byte.parseByte("118"),
      java.lang.Byte.parseByte("101"),
      java.lang.Byte.parseByte("114"),
      java.lang.Byte.parseByte("115"),
      java.lang.Byte.parseByte("105"),
      java.lang.Byte.parseByte("111"),
      java.lang.Byte.parseByte("110")
    )
    val q = scala.collection.mutable.Queue[Byte]()

    Stream.continually[java.lang.Byte]({
          try {
            val b = stdin.readByte
            b
          } catch {
            case e: java.io.EOFException => null
            case e: Throwable => throw e
          }
    })
    .takeWhile(x => x != null)
    .foreach(b => {
      if (q.size >= 18) {
        q.dequeue
      }
      q += b
      numBytes+=1
      currentStream.writeByte(b.intValue)
      if (!sawParquetMrVersion) {
        sawParquetMrVersion = q.toVector == PARQUET_MR_VERSION
      }
      if ((q.toVector.endsWith(PAR1_BEG) && numParOnes % 2 == 1) || (q.toVector.endsWith(PAR1_END) && sawParquetMrVersion)) {
        if (numParOnes % 2 == 0) {
          fileNames += currentFile.getPath
          currentStream.close
          currentFile = java.io.File.createTempFile("parquet-dumper", ".parquet")
          currentFile.deleteOnExit
          currentStream = new java.io.DataOutputStream( new java.io.BufferedOutputStream( new java.io.FileOutputStream(currentFile)))
        } else {
          sawParquetMrVersion = false
        }
        numParOnes += 1
      }
    })
    stdin.close
    fileNames
  }
}
