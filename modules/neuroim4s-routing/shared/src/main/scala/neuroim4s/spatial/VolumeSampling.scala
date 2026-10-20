package neuroim4s.spatial

import image4s.geometry.GeometryError
import neuroim4s.image.{GridSpec, Indexing, SpatialDims, SpatialPoint}
import gale.linalg.LinAlgError
import gale.sparse.{CSR, Sparse, SparseValuePolicy}
import scala.collection.mutable.ArrayBuffer

enum SamplingPolicy:
  case Nearest, Trilinear

enum VolumeSamplingError[+E]:
  case Pullback(cause: E)
  case Geometry(cause: GeometryError) extends VolumeSamplingError[Nothing]
  case Assembly(cause: LinAlgError) extends VolumeSamplingError[Nothing]
  case InvalidRows(reason: String) extends VolumeSamplingError[Nothing]

final case class SampledVolumeOperator(map: CSR, coverage: Vector[Double]):
  require(coverage.length == map.rows, "coverage must match sampled rows")
  require(coverage.forall(value => value.isFinite && value >= 0.0 && value <= 1.0), "coverage must be finite and between zero and one")

/** Assemble one volume sampling pass, preserving target-row order and provider-owned geometry. */
object VolumeSampling:
  def assemble[E](
      source: GridSpec[?],
      target: GridSpec[?],
      targetRows: Vector[Int],
      pullback: SpatialPoint => Either[E, SpatialPoint],
      sampling: SamplingPolicy
  ): Either[VolumeSamplingError[E], SampledVolumeOperator] =
    val targetSize = target.shape.toVector.product
    if targetRows.exists(row => row < 0 || row >= targetSize) || targetRows.distinct.length != targetRows.length then
      Left(VolumeSamplingError.InvalidRows("target rows must be unique and within the target volume"))
    else
      for
        assembly <- assembleRows(source, target, targetRows, pullback, sampling)
        builder <- Sparse.cooChecked(targetRows.length, source.shape.toVector.product, SparseValuePolicy.RequireFinite)
          .left.map(VolumeSamplingError.Assembly.apply)
        _ <-
          var i = 0
          var failure = Option.empty[VolumeSamplingError[E]]
          while i < assembly.values.length && failure.isEmpty do
            builder.tryAdd(assembly.rowIndices(i), assembly.colIndices(i), assembly.values(i)) match
              case Left(error) => failure = Some(VolumeSamplingError.Assembly(error))
              case Right(()) => ()
            i += 1
          failure.toLeft(())
        coo <- builder.tryToCOO().left.map(VolumeSamplingError.Assembly.apply)
      yield SampledVolumeOperator(coo.toCSR, assembly.coverage.toVector)

  private def assembleRows[E](
    sourceGrid: GridSpec[?],
    targetGrid: GridSpec[?],
    targetRows: Vector[Int],
    pullback: SpatialPoint => Either[E, SpatialPoint],
    sampling: SamplingPolicy
  ): Either[VolumeSamplingError[E], RowAssembly] =
    val sourceDims = sourceGrid.shape
    val targetDims = targetGrid.shape
    val assembly = RowAssembly()

    var outRow = 0
    var error = Option.empty[VolumeSamplingError[E]]
    while outRow < targetRows.length && error.isEmpty do
      val targetIndex = targetRows(outRow)
      val targetVoxel = Indexing.indexToGrid3D(targetDims, targetIndex)
      val targetWorld =
        targetGrid.voxelToWorld(SpatialPoint(targetVoxel.x.toDouble, targetVoxel.y.toDouble, targetVoxel.z.toDouble))
      pullback(targetWorld) match
        case Left(err) =>
          error = Some(VolumeSamplingError.Pullback(err))
        case Right(sourceWorld) =>
          sourceGrid.worldToVoxel(sourceWorld) match
            case Left(err) =>
              error = Some(VolumeSamplingError.Geometry(err))
            case Right(sourceVoxel) =>
              val weights =
                sampling match
                  case SamplingPolicy.Nearest =>
                    nearestWeights(sourceDims, sourceVoxel)
                  case SamplingPolicy.Trilinear =>
                    trilinearWeights(sourceDims, sourceVoxel)
              assembly.coverage += weights.coverage
              var j = 0
              while j < weights.cols.length do
                assembly.rowIndices += outRow
                assembly.colIndices += weights.cols(j)
                assembly.values += weights.values(j)
                j += 1
      outRow += 1

    error match
      case Some(err) => Left(err)
      case None => Right(assembly)

  private def nearestWeights(sourceDims: SpatialDims, voxel: SpatialPoint): RowWeights =
    val x = math.round(voxel.x).toInt
    val y = math.round(voxel.y).toInt
    val z = math.round(voxel.z).toInt
    if inBounds(sourceDims, x, y, z) then
      RowWeights(Vector(Indexing.gridToIndex3D(sourceDims, x, y, z)), Vector(1.0), 1.0)
    else RowWeights.empty

  private def trilinearWeights(sourceDims: SpatialDims, voxel: SpatialPoint): RowWeights =
    val x0 = math.floor(voxel.x).toInt
    val y0 = math.floor(voxel.y).toInt
    val z0 = math.floor(voxel.z).toInt
    val xd = voxel.x - x0.toDouble
    val yd = voxel.y - y0.toDouble
    val zd = voxel.z - z0.toDouble

    val cols = ArrayBuffer.empty[Int]
    val values = ArrayBuffer.empty[Double]
    var coverage = 0.0

    addTrilinearCorner(sourceDims, x0, y0, z0, (1.0 - xd) * (1.0 - yd) * (1.0 - zd), cols, values) match
      case Some(weight) => coverage += weight
      case None => ()
    addTrilinearCorner(sourceDims, x0 + 1, y0, z0, xd * (1.0 - yd) * (1.0 - zd), cols, values) match
      case Some(weight) => coverage += weight
      case None => ()
    addTrilinearCorner(sourceDims, x0, y0 + 1, z0, (1.0 - xd) * yd * (1.0 - zd), cols, values) match
      case Some(weight) => coverage += weight
      case None => ()
    addTrilinearCorner(sourceDims, x0 + 1, y0 + 1, z0, xd * yd * (1.0 - zd), cols, values) match
      case Some(weight) => coverage += weight
      case None => ()
    addTrilinearCorner(sourceDims, x0, y0, z0 + 1, (1.0 - xd) * (1.0 - yd) * zd, cols, values) match
      case Some(weight) => coverage += weight
      case None => ()
    addTrilinearCorner(sourceDims, x0 + 1, y0, z0 + 1, xd * (1.0 - yd) * zd, cols, values) match
      case Some(weight) => coverage += weight
      case None => ()
    addTrilinearCorner(sourceDims, x0, y0 + 1, z0 + 1, (1.0 - xd) * yd * zd, cols, values) match
      case Some(weight) => coverage += weight
      case None => ()
    addTrilinearCorner(sourceDims, x0 + 1, y0 + 1, z0 + 1, xd * yd * zd, cols, values) match
      case Some(weight) => coverage += weight
      case None => ()

    if coverage > 0.0 then
      var i = 0
      while i < values.length do
        values(i) = values(i) / coverage
        i += 1

    // The eight corner weights sum to one only up to rounding; coverage is a fraction and must stay in [0, 1].
    RowWeights(cols.toVector, values.toVector, math.min(coverage, 1.0))

  private def addTrilinearCorner(
    dims: SpatialDims,
    x: Int,
    y: Int,
    z: Int,
    weight: Double,
    cols: ArrayBuffer[Int],
    values: ArrayBuffer[Double]
  ): Option[Double] =
    if weight == 0.0 || !inBounds(dims, x, y, z) then None
    else
      cols += Indexing.gridToIndex3D(dims, x, y, z)
      values += weight
      Some(weight)

  private inline def inBounds(dims: SpatialDims, x: Int, y: Int, z: Int): Boolean =
    x >= 0 && x < dims.x &&
      y >= 0 && y < dims.y &&
      z >= 0 && z < dims.z


private final case class RowWeights(cols: Vector[Int], values: Vector[Double], coverage: Double)

private object RowWeights:
  val empty: RowWeights =
    RowWeights(Vector.empty, Vector.empty, 0.0)

private final case class RowAssembly(
  rowIndices: ArrayBuffer[Int] = ArrayBuffer.empty[Int],
  colIndices: ArrayBuffer[Int] = ArrayBuffer.empty[Int],
  values: ArrayBuffer[Double] = ArrayBuffer.empty[Double],
  coverage: ArrayBuffer[Double] = ArrayBuffer.empty[Double]
)
