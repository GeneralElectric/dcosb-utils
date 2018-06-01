package io.predix.dcosb.util

import java.io.{File, InputStream}

import scala.util.{Failure, Success, Try}

object FileUtils {

  // takes a reference to a file on the classPath ( relative )
  // or a file anywhere on the filesystem ( absolute path )
  def file(path: String): Try[Array[Byte]] = {

    val f = new File(path)
    if (f.exists && f.canRead) {
      val source = scala.io.Source.fromFile(path).bufferedReader()
      try Success(Stream.continually(source.read).takeWhile(-1 !=).map(_.toByte).toArray)
        catch {
          case e: Throwable => Failure(e)
        }
      finally source.close()
    } else {
      // see if this is on the classpath
      Option[InputStream](getClass().getClassLoader().getResourceAsStream(path)) match {

        case Some(i) => Success(readToByteArray(i))
        case _       => Failure(new IllegalArgumentException(s"Could not open and read $path"))
      }
    }

  }

  def fileExists(path: String): Boolean = {
    (new File(path)).exists || getClass()
      .getClassLoader()
      .getResources(path) != null
  }

  private def readToByteArray(is: InputStream): Array[Byte] = {
    try Stream.continually(is.read).takeWhile(-1 !=).map(_.toByte).toArray
    finally is.close()
  }

}
