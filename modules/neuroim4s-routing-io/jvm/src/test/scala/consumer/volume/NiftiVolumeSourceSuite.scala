package consumer.volume

import image4s.geometry.Affine
import image4s.geometry.D3
import neuroim4s.image.SampleSpaces
import neuroim4s.spaces.{SpaceEvidence, WorldSpace}
import neuroim4s.spatial.{VolumeGrid, VolumeReadError, VolumeReadRequest}
import neuroim4s.spatial.io.{NiftiVolumeSource, NiftiVolumeSourceStats}
import java.nio.{ByteBuffer, ByteOrder}
import java.nio.file.{Files, Path, StandardOpenOption}

class NiftiVolumeSourceSuite extends munit.FunSuite:
  private val world = WorldSpace.declare("scoped-read-fixture").toOption.get
  private val evidence = SpaceEvidence(assertion = Some(world))
  private val space = SampleSpaces.inWorld(SampleSpaces(Vector(2, 2, 2), affine = Some(Affine.identity[D3])), world).toOption.get
  private val grid = VolumeGrid.from(space).toOption.get

  private def withPath(body: Path => Unit): Unit =
    val directory = Files.createTempDirectory("neuroim4s-scoped-read-")
    val path = directory.resolve("fixture.nii")
    try body(path)
    finally
      val _ = Files.deleteIfExists(path)
      val _ = Files.deleteIfExists(directory)

  /** A hand-written x-fastest NIfTI witness, independent of the production writer and canonical reader. */
  private def write(path: Path, order: ByteOrder, truncated: Boolean = false): Unit =
    val bytes = new Array[Byte](352 + (if truncated then 1 else 32))
    val buffer = ByteBuffer.wrap(bytes).order(order)
    val _ = buffer.putInt(0, 348)
    val _ = buffer.putShort(40, 4.toShort)
    (0 until 4).foreach(i => { val _ = buffer.putShort(42 + 2 * i, 2.toShort) })
    val _ = buffer.putShort(70, 4.toShort)
    val _ = buffer.putShort(72, 16.toShort)
    (0 until 5).foreach(i => { val _ = buffer.putFloat(76 + 4 * i, 1.0f) })
    val _ = buffer.putFloat(108, 352.0f)
    val _ = buffer.putFloat(112, 2.0f)
    val _ = buffer.putFloat(116, 3.0f)
    val _ = buffer.putShort(254, 1.toShort)
    val _ = buffer.putFloat(280, 1.0f)
    val _ = buffer.putFloat(300, 1.0f)
    val _ = buffer.putFloat(320, 1.0f)
    val _ = buffer.put(344, 'n'.toByte)
    val _ = buffer.put(345, '+'.toByte)
    val _ = buffer.put(346, '1'.toByte)
    if !truncated then
      for observation <- 0 until 2; z <- 0 until 2; y <- 0 until 2; x <- 0 until 2 do
        val ordinal = x + 2 * (y + 2 * (z + 2 * observation))
        val _ = buffer.putShort(352 + 2 * ordinal, (100 * observation + 10 * x + 2 * y + z).toShort)
    val _ = Files.write(path, bytes)

  test("preparation is lazy; exact three-axis order, endian decoding and scaling survive scoped reads"):
    Vector(ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN).foreach: order =>
      withPath: path =>
        val source = NiftiVolumeSource.prepare(path, grid, evidence, 2).toOption.get
        assertEquals(source.stats, NiftiVolumeSourceStats(0, 0, 0, 0, 0, 0))
        assert(source.validate().left.exists { case VolumeReadError.Unavailable(_) => true; case _ => false })
        write(path, order)
        val request = VolumeReadRequest.make(8, 2, Vector(7, 1, 4), Vector(1, 0)).toOption.get
        val result = source.read(request).toOption.get.data
        val expected = Vector(Vector(229.0, 29.0), Vector(205.0, 5.0), Vector(223.0, 23.0))
        for r <- 0 until 3; c <- 0 until 2 do assertEqualsDouble(result(r, c), expected(r)(c), 0.0)
        assertEquals(source.stats.channelOpens, 1L)
        assertEquals(source.stats.bytesRead, 12L)
        assertEquals(source.stats.readWindows, 6L)
        assert(Files.deleteIfExists(path), "source must close its channel on return")

  test("freshness is checked after a successful read; empty support opens no data channel"):
    withPath: path =>
      write(path, ByteOrder.LITTLE_ENDIAN)
      val source = NiftiVolumeSource.prepare(path, grid, evidence, 2).toOption.get
      val empty = VolumeReadRequest.make(8, 2, Vector.empty, Vector(1)).toOption.get
      assertEquals(source.read(empty).toOption.get.data.rows, 0)
      assertEquals(source.stats.channelOpens, 0L)
      val _ = Files.write(path, Array(0.toByte), StandardOpenOption.APPEND)
      assert(source.validate().left.exists { case VolumeReadError.Stale(_, _) => true; case _ => false })
      assertEquals(source.stats.headerReads, 1L)

  test("different frame owner and truncated payload refuse before returning partial data"):
    withPath: path =>
      write(path, ByteOrder.LITTLE_ENDIAN)
      val other = WorldSpace.declare("other-scoped-world").toOption.get
      val wrong = NiftiVolumeSource.prepare(path, grid, SpaceEvidence(assertion = Some(other)), 2).toOption.get
      assert(wrong.validate().left.exists { case VolumeReadError.GridMismatch(_) => true; case _ => false })
      assertEquals(wrong.stats.channelOpens, 0L)
      write(path, ByteOrder.LITTLE_ENDIAN, truncated = true)
      val source = NiftiVolumeSource.prepare(path, grid, evidence, 2).toOption.get
      val request = VolumeReadRequest.make(8, 2, Vector(7), Vector(1)).toOption.get
      assert(source.read(request).left.exists { case VolumeReadError.ReadFailed(_) => true; case _ => false })
      assertEquals(source.stats.channelOpens, 1L)
      assert(Files.deleteIfExists(path), "failure must close its data channel")

  test("compressed sources are explicitly refused without filesystem access"):
    assertEquals(NiftiVolumeSource.prepare(Path.of("missing-fixture.nii.gz"), grid, evidence, 2),
      Left(VolumeReadError.Unavailable("compressed NIfTI requires an explicitly staged uncompressed file")))
