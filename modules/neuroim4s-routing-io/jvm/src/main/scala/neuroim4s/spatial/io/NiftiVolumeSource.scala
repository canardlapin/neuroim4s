package neuroim4s.spatial.io

import image4s.nifti.NiftiHeader

import image4s.geometry.Grid
import neuroim4s.image.{SampleSpaceError, SampleSpaces}
import neuroim4s.image.io.{Nifti}
import neuroim4s.spaces.SpaceEvidence
import neuroim4s.spatial.*
import gale.linalg.DMat

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{Files, Path}
import scala.util.Using
import scala.util.control.NonFatal

final case class NiftiVolumeSourceStats(
  validationCalls: Long,
  readCalls: Long,
  headerReads: Long,
  channelOpens: Long,
  readWindows: Long,
  bytesRead: Long
)

private final case class NiftiFileStamp(size: Long, modifiedMillis: Long):
  def fingerprint: String =
    s"size=$size,mtime=$modifiedMillis"

private final case class ValidatedNiftiState(
  stamp: NiftiFileStamp,
  header: NiftiHeader
)

private final case class NiftiReadWindow(
  startVoxel: Int,
  voxelCount: Int,
  outputRows: Vector[Int]
):
  def byteCount(bytesPerValue: Int): Int =
    Math.multiplyExact(voxelCount, bytesPerValue)

final class NiftiVolumeSource private (
  val path: Path,
  val evidence: SpaceEvidence,
  override val grid: VolumeGrid,
  override val observations: Int
) extends VolumeSource:
  private var baseline = Option.empty[NiftiFileStamp]
  private var cachedState = Option.empty[ValidatedNiftiState]
  private var validationCount = 0L
  private var readCount = 0L
  private var headerReadCount = 0L
  private var channelOpenCount = 0L
  private var windowReadCount = 0L
  private var byteReadCount = 0L

  override def validate(): Either[VolumeReadError, Unit] =
    this.synchronized {
      validationCount += 1L
      resolveState().map(_ => ())
    }

  override def read(request: VolumeReadRequest): Either[VolumeReadError, VolumeBlock] =
    this.synchronized {
      readCount += 1L
      for
        checkedRequest <- VolumeReadRequest.make(grid.size, observations, request.sourceRows, request.observations)
        _ <- if checkedRequest == request then Right(()) else Left(VolumeReadError.RequestMismatch)
        state <- resolveState()
        block <- readBlock(state.header, checkedRequest)
      yield block
    }

  def stats: NiftiVolumeSourceStats =
    this.synchronized {
      NiftiVolumeSourceStats(
        validationCalls = validationCount,
        readCalls = readCount,
        headerReads = headerReadCount,
        channelOpens = channelOpenCount,
        readWindows = windowReadCount,
        bytesRead = byteReadCount
      )
    }

  private def resolveState(): Either[VolumeReadError, ValidatedNiftiState] =
    currentStamp().flatMap { stamp =>
      baseline match
        case Some(expected) if expected != stamp =>
          Left(VolumeReadError.Stale(expected.fingerprint, stamp.fingerprint))
        case _ =>
          cachedState match
            case Some(state) if state.stamp == stamp =>
              Right(state)
            case _ =>
              readAndValidateHeader(stamp).map { state =>
                if baseline.isEmpty then baseline = Some(stamp)
                cachedState = Some(state)
                state
              }
    }

  private def currentStamp(): Either[VolumeReadError, NiftiFileStamp] =
    try
      if !Files.isRegularFile(path) then
        Left(VolumeReadError.Unavailable(s"NIfTI file does not exist: $path"))
      else
        val attributes = Files.readAttributes(path, classOf[BasicFileAttributes])
        Right(NiftiFileStamp(attributes.size(), attributes.lastModifiedTime().toMillis))
    catch
      case NonFatal(error) =>
        Left(VolumeReadError.Unavailable(detail(error)))

  private def readAndValidateHeader(stamp: NiftiFileStamp): Either[VolumeReadError, ValidatedNiftiState] =
    try
      headerReadCount += 1L
      image4s.nifti.Nifti
        .readHeader(path)
        .left
        .map(error => VolumeReadError.ReadFailed(error.message))
        .flatMap: header =>
          validateHeader(header).map(_ => ValidatedNiftiState(stamp, header))
    catch
      case NonFatal(error) =>
        Left(VolumeReadError.ReadFailed(detail(error)))

  private def validateHeader(header: NiftiHeader): Either[VolumeReadError, Unit] =
    val bytesPerValue = header.datatype.bitsPerValue / 8
    val supported =
      (header.datatype.code == 2 && bytesPerValue == 1) ||
        (header.datatype.code == 4 && bytesPerValue == 2) ||
        (header.datatype.code == 8 && bytesPerValue == 4) ||
        (header.datatype.code == 16 && bytesPerValue == 4) ||
        (header.datatype.code == 64 && bytesPerValue == 8)
    val expectedSpace = grid.space
    val admittedHeaderSpace =
      if header.dimensions.length < 3 then
        Left(
          SampleSpaceError.ExpectedDimensionality(
            "D3 sample space",
            expected = 3,
            actual = header.dimensions.length
          )
        )
      else
        Nifti.spaceIn(header, evidence)
          .left
          .map(error => SampleSpaceError.InvalidArgument(error.message))
          .flatMap(space => SampleSpaces.requireVolumeD3(space.typed.spatialOnly))
    admittedHeaderSpace
      .left
      .map(error => VolumeReadError.Admission(error))
      .flatMap: actualSpace =>
        if header.dimensions.length > 4 then
          Left(VolumeReadError.ReadFailed(s"expected a 3D or 4D NIfTI, got ${header.dimensions.mkString("x")}"))
        else if !supported then
          Left(VolumeReadError.ReadFailed(s"unsupported NIfTI datatype ${header.datatype.code} with bitpix ${header.datatype.bitsPerValue}"))
        else if header.voxelOffset < 0 then
          Left(VolumeReadError.ReadFailed(s"negative vox_offset ${header.voxelOffset}"))
        else
          val actualRows = header.dimensions.take(3).product
          val actualObservations = if header.dimensions.length == 4 then header.dimensions(3) else 1
          if actualRows != grid.size then
            Left(VolumeReadError.ShapeMismatch(grid.size, actualRows))
          else if actualObservations != observations then
            Left(VolumeReadError.ObservationMismatch(observations, actualObservations))
          else
            Grid
              .exactCongruence(expectedSpace.grid, actualSpace.grid)
              .left
              .map(error => VolumeReadError.GridMismatch(error))
              .map(_ => ())

  private def readBlock(
    header: NiftiHeader,
    request: VolumeReadRequest
  ): Either[VolumeReadError, VolumeBlock] =
    val valueCount = request.rows.toLong * request.columns.toLong
    if valueCount > Int.MaxValue.toLong then
      Left(VolumeReadError.ReadFailed(s"requested block has $valueCount values"))
    else if request.rows == 0 then
      Right(VolumeBlock(request, DMat.newBuilder(0, request.columns).result()))
    else
      try
        val bytesPerValue = header.datatype.bitsPerValue / 8
        val windows = readWindows(request.sourceRows, header.dimensions.take(3))
        val maximumBytes = windows.map(_.byteCount(bytesPerValue)).max
        val buffer = ByteBuffer.allocateDirect(maximumBytes).order(header.byteOrder match
          case image4s.nifti.NiftiByteOrder.LittleEndian => java.nio.ByteOrder.LITTLE_ENDIAN
          case image4s.nifti.NiftiByteOrder.BigEndian => java.nio.ByteOrder.BIG_ENDIAN
        )
        val values = new Array[Double](valueCount.toInt)
        val slope = if header.slope == 0.0 then 1.0 else header.slope

        Using.resource(FileChannel.open(path, READ)) { channel =>
          channelOpenCount += 1L
          var outputColumn = 0
          while outputColumn < request.observations.length do
            val observation = request.observations(outputColumn)
            val frameElement = Math.multiplyExact(observation.toLong, grid.size.toLong)
            var windowIndex = 0
            while windowIndex < windows.length do
              val window = windows(windowIndex)
              val firstElement = Math.addExact(frameElement, window.startVoxel.toLong)
              val byteOffset = Math.multiplyExact(firstElement, bytesPerValue.toLong)
              val position = Math.addExact(header.voxelOffset.toLong, byteOffset)
              val bytes = window.byteCount(bytesPerValue)
              val _ = buffer.clear()
              val _ = buffer.limit(bytes)
              readFully(channel, buffer, position)
              windowReadCount += 1L
              byteReadCount += bytes.toLong

              var voxelOffset = 0
              while voxelOffset < window.voxelCount do
                val outputRow = window.outputRows(voxelOffset)
                values(outputRow * request.columns + outputColumn) =
                  decodeAt(buffer, header.datatype.code, voxelOffset * bytesPerValue) * slope + header.intercept
                voxelOffset += 1
              windowIndex += 1
            outputColumn += 1
        }
        val builder = DMat.newBuilder(request.rows, request.columns)
        var index = 0
        while index < values.length do
          builder.writeLinear(index, values(index))
          index += 1
        Right(VolumeBlock(request, builder.result()))
      catch
        case NonFatal(error) =>
          Left(VolumeReadError.ReadFailed(detail(error)))

  private def readWindows(
    sourceRows: Vector[Int],
    spatialDims: Vector[Int]
  ): Vector[NiftiReadWindow] =
    val nx = spatialDims(0)
    val ny = spatialDims(1)
    val nz = spatialDims(2)
    val indexed =
      sourceRows.zipWithIndex.map { case (canonicalOrdinal, outputRow) =>
        val z = canonicalOrdinal % nz
        val xy = canonicalOrdinal / nz
        val y = xy % ny
        val x = xy / ny
        val niftiOrdinal = x + nx * (y + ny * z)
        niftiOrdinal -> outputRow
      }.sortBy(_._1)
    val windows = Vector.newBuilder[NiftiReadWindow]
    var start = indexed.head._1
    var previous = start
    var outputs = Vector(indexed.head._2)
    var i = 1
    while i < indexed.length do
      val (voxel, outputRow) = indexed(i)
      if voxel == previous + 1 then
        previous = voxel
        outputs = outputs :+ outputRow
      else
        windows += NiftiReadWindow(start, previous - start + 1, outputs)
        start = voxel
        previous = voxel
        outputs = Vector(outputRow)
      i += 1
    windows += NiftiReadWindow(start, previous - start + 1, outputs)
    windows.result()

  private def readFully(channel: FileChannel, buffer: ByteBuffer, position: Long): Unit =
    var offset = 0L
    while buffer.hasRemaining do
      val count = channel.read(buffer, position + offset)
      if count < 0 then throw new IllegalArgumentException(s"unexpected EOF at byte ${position + offset}")
      if count == 0 then throw new IllegalArgumentException(s"unable to read byte ${position + offset}")
      offset += count.toLong

  private def decodeAt(buffer: ByteBuffer, datatype: Int, offset: Int): Double =
    datatype match
      case 2  => (buffer.get(offset) & 0xff).toDouble
      case 4  => buffer.getShort(offset).toDouble
      case 8  => buffer.getInt(offset).toDouble
      case 16 => buffer.getFloat(offset).toDouble
      case 64 => buffer.getDouble(offset)
      case other => throw new UnsupportedOperationException(s"unsupported NIfTI datatype $other")

  private def detail(error: Throwable): String =
    Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getSimpleName)

object NiftiVolumeSource:
  /** No filesystem access here. The first validation pins the backing file revision. */
  def prepare(path: Path, grid: VolumeGrid, evidence: SpaceEvidence, observations: Int): Either[VolumeReadError, NiftiVolumeSource] =
    val normalized = path.toAbsolutePath.normalize()
    if normalized.toString.endsWith(".gz") then
      Left(VolumeReadError.Unavailable("compressed NIfTI requires an explicitly staged uncompressed file"))
    else if observations <= 0 then Left(VolumeReadError.ReadFailed("volume source observations must be positive"))
    else Right(new NiftiVolumeSource(normalized, evidence, grid, observations))
