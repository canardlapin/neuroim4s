package neuroim4s.spatial

import gale.linalg.{DMat, LinAlgError}
import image4s.geometry.Grid

enum VolumeExecutionError[+N, +E]:
  case Routing(cause: VolumeRoutingError[N, E])
  case UnsampledDomain(id: N)
  case Sampling(cause: VolumeSamplingError[VolumeRoutingError[N, E]])
  case Read(cause: VolumeReadError) extends VolumeExecutionError[Nothing, Nothing]
  case Linear(cause: LinAlgError) extends VolumeExecutionError[Nothing, Nothing]

final class CompiledVolumeRoute[N, E] private[spatial] (
    val route: VolumeRoute[N, E],
    val sourceGrid: VolumeGrid,
    val targetRows: Vector[Int],
    val operator: SampledVolumeOperator
):
  /** Every stored sparse column is support, including structural zero entries. */
  def sourceRows: Vector[Int] =
    val columns = scala.collection.mutable.HashSet.empty[Int]
    operator.map.foreachStoredEntry: (_, column, _) =>
      val _ = columns.add(column)
    columns.toVector.sorted

  def materialize(source: VolumeSource, observations: Vector[Int]): Either[VolumeExecutionError[N, E], DMat] =
    for
      _ <- Grid.exactCongruence(sourceGrid.space.grid, source.grid.space.grid)
        .left.map(error => VolumeExecutionError.Read(VolumeReadError.GridMismatch(error)))
      request <- VolumeReadRequest.make(sourceGrid.size, source.observations, sourceRows, observations).left.map(VolumeExecutionError.Read.apply)
      values <- VolumeMaterialization.readExpanded(source, request).left.map(VolumeExecutionError.Read.apply)
      result <- operator.map.applyTo(values).left.map(VolumeExecutionError.Linear.apply)
    yield result

object VolumeExecution:
  def compile[N, E](graph: VolumeGraph[N, E], source: N, target: N, targetRows: Vector[Int],
      sampling: SamplingPolicy = SamplingPolicy.Trilinear, allowInverses: Boolean = false,
      inversePenalty: Double = 1.0): Either[VolumeExecutionError[N, E], CompiledVolumeRoute[N, E]] =
    for
      from <- graph.domain(source).left.map(VolumeExecutionError.Routing.apply)
      onto <- graph.domain(target).left.map(VolumeExecutionError.Routing.apply)
      sourceGrid <- from.sampling.toRight(VolumeExecutionError.UnsampledDomain(source))
      targetGrid <- onto.sampling.toRight(VolumeExecutionError.UnsampledDomain(target))
      route <- graph.path(source, target, allowInverses, inversePenalty).left.map(VolumeExecutionError.Routing.apply)
      _ <- route.steps.collectFirst { case step if step.registration.map.isEmpty => step.registration.id } match
        case Some(id) => Left(VolumeExecutionError.Routing(VolumeRoutingError.MissingMap(id)))
        case None => Right(())
      sampled <- VolumeSampling.assemble(sourceGrid.spec, targetGrid.spec, targetRows, route.pullback, sampling)
        .left.map(VolumeExecutionError.Sampling.apply)
    yield new CompiledVolumeRoute(route, sourceGrid, targetRows, sampled)
