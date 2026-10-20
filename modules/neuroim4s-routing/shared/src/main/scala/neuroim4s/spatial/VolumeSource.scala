package neuroim4s.spatial

import gale.linalg.DMat
import image4s.geometry.GeometryError
import neuroim4s.image.SampleSpaceError

enum VolumeReadError:
  case Unavailable(reason: String)
  case Stale(expected: String, actual: String)
  case ReadFailed(reason: String)
  case Admission(cause: SampleSpaceError)
  case GridMismatch(cause: GeometryError)
  case ShapeMismatch(expected: Int, actual: Int)
  case ObservationMismatch(expected: Int, actual: Int)
  case IndexOutOfBounds(axis: String, index: Int, limit: Int)
  case DuplicateIndex(axis: String, index: Int)
  case BlockShapeMismatch(expectedRows: Int, actualRows: Int, expectedColumns: Int, actualColumns: Int)
  case RequestMismatch

/** A checked compact read in caller order. Volume identity and field provenance remain caller-owned. */
final case class VolumeReadRequest private (
    totalRows: Int,
    totalObservations: Int,
    sourceRows: Vector[Int],
    observations: Vector[Int]
):
  require(totalRows > 0 && totalObservations > 0, "volume source shape must be positive")
  require(sourceRows.distinct.length == sourceRows.length && sourceRows.forall(i => i >= 0 && i < totalRows), "source rows must be unique and in bounds")
  require(observations.distinct.length == observations.length && observations.forall(i => i >= 0 && i < totalObservations), "observations must be unique and in bounds")

  def rows: Int = sourceRows.length
  def columns: Int = observations.length

  /** Expand only for the existing Gale operator contract; unrequested rows remain zero. */
  def expand(block: DMat): Either[VolumeReadError, DMat] =
    val valueCount = totalRows.toLong * columns.toLong
    if block.rows != rows || block.cols != columns then
      Left(VolumeReadError.BlockShapeMismatch(rows, block.rows, columns, block.cols))
    else if valueCount > Int.MaxValue.toLong then
      Left(VolumeReadError.ReadFailed(s"expanded block has $valueCount values"))
    else
      val expanded = DMat.newBuilder(totalRows, columns)
      var row = 0
      while row < rows do
        var column = 0
        while column < columns do
          expanded.writeLinear(sourceRows(row) * columns + column, block(row, column))
          column += 1
        row += 1
      Right(expanded.result())

object VolumeReadRequest:
  def make(totalRows: Int, totalObservations: Int, sourceRows: Vector[Int], observations: Vector[Int]): Either[VolumeReadError, VolumeReadRequest] =
    if totalRows <= 0 then Left(VolumeReadError.ReadFailed("volume source rows must be positive"))
    else if totalObservations <= 0 then Left(VolumeReadError.ReadFailed("volume source observations must be positive"))
    else
      for
        _ <- validateIndices("source row", sourceRows, totalRows)
        _ <- validateIndices("observation", observations, totalObservations)
      yield new VolumeReadRequest(totalRows, totalObservations, sourceRows, observations)

  private def validateIndices(axis: String, indices: Vector[Int], limit: Int): Either[VolumeReadError, Unit] =
    val seen = scala.collection.mutable.HashSet.empty[Int]
    var i = 0
    while i < indices.length do
      val index = indices(i)
      if index < 0 || index >= limit then return Left(VolumeReadError.IndexOutOfBounds(axis, index, limit))
      if seen.contains(index) then return Left(VolumeReadError.DuplicateIndex(axis, index))
      seen += index
      i += 1
    Right(())

final case class VolumeBlock(request: VolumeReadRequest, data: DMat):
  require(data.rows == request.rows && data.cols == request.columns, "volume block must match its request")

trait VolumeSource:
  def grid: VolumeGrid
  def observations: Int
  /** Availability, freshness and geometry admission; release resources before returning. */
  def validate(): Either[VolumeReadError, Unit]
  /** One compact block in request order; close every acquired resource before returning. */
  def read(request: VolumeReadRequest): Either[VolumeReadError, VolumeBlock]

/** Standalone anatomical execution, without dataset identities, caches or functional value transforms. */
object VolumeMaterialization:
  def readExpanded(source: VolumeSource, request: VolumeReadRequest): Either[VolumeReadError, DMat] =
    for
      _ <- source.validate()
      checked <- VolumeReadRequest.make(source.grid.size, source.observations, request.sourceRows, request.observations)
      _ <- if checked == request then Right(()) else Left(VolumeReadError.RequestMismatch)
      block <- source.read(checked)
      _ <- if block.request == checked then Right(()) else Left(VolumeReadError.RequestMismatch)
      expanded <- checked.expand(block.data)
    yield expanded
