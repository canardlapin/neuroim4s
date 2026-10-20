package neuroim4s.transform.oracle

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import scala.scalajs.js.typedarray.{Int8Array, Uint8Array}

@js.native
@JSImport("fs", JSImport.Namespace)
private object NodeFs extends js.Object:
  def readFileSync(path: String): Uint8Array = js.native
  def existsSync(path: String): Boolean = js.native

@js.native
@JSImport("zlib", JSImport.Namespace)
private object NodeZlib extends js.Object:
  def gunzipSync(data: Uint8Array): Uint8Array = js.native

@js.native
@JSImport("path", JSImport.Namespace)
private object NodePath extends js.Object:
  def join(parts: String*): String = js.native

/** Reads the shared test resources from disk: Scala.js test runs do not bundle resources. */
private[oracle] object OracleFixturePlatform:
  private val resourceRoot = OracleResourceLocation.root

  def read(resource: String): Array[Byte] =
    val path = NodePath.join(resourceRoot, resource)
    if !NodeFs.existsSync(path) then throw new IllegalStateException(s"missing oracle fixture on disk: $path")
    toBytes(NodeFs.readFileSync(path))

  def gunzip(bytes: Array[Byte]): Array[Byte] =
    val input = new Uint8Array(bytes.length)
    var i = 0
    while i < bytes.length do
      input(i) = (bytes(i) & 0xff).toShort
      i += 1
    toBytes(NodeZlib.gunzipSync(input))

  private def toBytes(data: Uint8Array): Array[Byte] =
    new Int8Array(data.buffer, data.byteOffset, data.length).toArray
