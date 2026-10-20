package neuroim4s.spatial

import cats.Hash
import cats.collections.HashMap
import graph4s.{ArcInput, Digraph}
import graph4s.algorithms.{DirectedRoutes, RouteChoice}
import graph4s.data.{ArcField, WeightedDigraph}
import neuroim4s.image.{SpatialPoint, SpatialPullbacks}
import reframe4s.core.SpatialMap

/** Only anatomical coordinate registrations belong here. Value transforms and surface mappings are downstream. */
enum VolumeMapKind:
  case Affine3D, Warp3D

enum VolumeRoutingError[+N, +E]:
  case MissingDomain(id: N)
  case DuplicateDomain(id: N)
  case DuplicateRegistration(id: E)
  case InvalidCost(value: Double) extends VolumeRoutingError[Nothing, Nothing]
  case InvalidQuality(value: Double) extends VolumeRoutingError[Nothing, Nothing]
  case InvalidPenalty(value: Double) extends VolumeRoutingError[Nothing, Nothing]
  case InvalidLimit(value: Int) extends VolumeRoutingError[Nothing, Nothing]
  case NoPath(source: N, target: N)
  case MissingMap(id: E)
  case MapFailure(cause: RegistrationMapError) extends VolumeRoutingError[Nothing, Nothing]
  case FrameMismatch(cause: reframe4s.core.MapError) extends VolumeRoutingError[Nothing, Nothing]
  case Topology(reason: String) extends VolumeRoutingError[Nothing, Nothing]

final class VolumeRegistration[N, E] private (
    val id: E,
    val source: N,
    val target: N,
    val kind: VolumeMapKind,
    val cost: Double,
    val inverseQuality: Option[Double],
    val map: Option[ProviderMapBinding]
)

object VolumeRegistration:
  def build[N, E](id: E, source: N, target: N, kind: VolumeMapKind, cost: Double,
      inverseQuality: Option[Double], map: Option[ProviderMapBinding]): Either[VolumeRoutingError[N, E], VolumeRegistration[N, E]] =
    if !cost.isFinite || cost < 0.0 then Left(VolumeRoutingError.InvalidCost(cost))
    else if kind == VolumeMapKind.Affine3D && map.exists(binding => !binding.isAffine) then
      Left(VolumeRoutingError.MapFailure(RegistrationMapError.InvalidProviderCoordinateMap("an affine registration requires a provider affine map")))
    else inverseQuality.find(q => !q.isFinite || q < 0.0 || q > 1.0) match
      case Some(q) => Left(VolumeRoutingError.InvalidQuality(q))
      case None => Right(new VolumeRegistration(id, source, target, kind, cost, inverseQuality, map))

final case class VolumeRouteStep[N, E](registration: VolumeRegistration[N, E], inverted: Boolean, cost: Double):
  def source: N = if inverted then registration.target else registration.source
  def target: N = if inverted then registration.source else registration.target
  def quality: Double = registration.inverseQuality.getOrElse(0.0)

final class VolumeRoute[N, E] private[spatial] (
    val source: N, val target: N, val steps: Vector[VolumeRouteStep[N, E]]
):
  def cost: Double = steps.map(_.cost).sum
  def quality: Double = if steps.isEmpty then 1.0 else steps.map(_.quality).min
  def usedInverses: Boolean = steps.exists(_.inverted)

  /** Apply coordinate maps target-to-source in the original route order, then sample values only once. */
  def pullback(point: SpatialPoint): Either[VolumeRoutingError[N, E], SpatialPoint] =
    var current = point
    var i = steps.length - 1
    var failure = Option.empty[VolumeRoutingError[N, E]]
    while i >= 0 && failure.isEmpty do
      val step = steps(i)
      val result = step.registration.map.toRight(VolumeRoutingError.MissingMap(step.registration.id)).flatMap: binding =>
        val directed = if step.inverted then binding.inverted.left.map(VolumeRoutingError.MapFailure.apply) else Right(binding)
        directed.flatMap: map =>
          SpatialPullbacks.transform(map.pullback, current)
            .left.map(error => VolumeRoutingError.MapFailure(RegistrationMapError.CoordinateTransformFailed(error.message)))
      result match
        case Right(value) => current = value
        case Left(error) => failure = Some(error)
      i -= 1
    failure.toLeft(current)

/** Forward-first anatomical routing over actual provider frame owners. Node and registration IDs remain caller-owned. */
final class VolumeGraph[N, E] private (
    val domains: Vector[VolumeDomain[N]],
    val registrations: Vector[VolumeRegistration[N, E]],
    private val nodes: HashMap[N, VolumeDomain[N]]
)(using Hash[N]):
  def domain(id: N): Either[VolumeRoutingError[N, E], VolumeDomain[N]] =
    nodes.get(id).toRight(VolumeRoutingError.MissingDomain(id))

  def path(source: N, target: N, allowInverses: Boolean = false, inversePenalty: Double = 1.0): Either[VolumeRoutingError[N, E], VolumeRoute[N, E]] =
    if !inversePenalty.isFinite || inversePenalty < 0.0 then Left(VolumeRoutingError.InvalidPenalty(inversePenalty))
    else
      for
        _ <- domain(source)
        _ <- domain(target)
        forward <- shortest(source, target, forwardSteps)
        result <- forward match
          case Some(route) => Right(route)
          case None if allowInverses => shortest(source, target, forwardSteps ++ inverseSteps(inversePenalty)).flatMap(_.toRight(VolumeRoutingError.NoPath(source, target)))
          case None => Left(VolumeRoutingError.NoPath(source, target))
      yield result

  def allPaths(source: N, target: N, maxPaths: Int = 10, allowInverses: Boolean = false,
      inversePenalty: Double = 1.0)(edgeLabel: E => String): Either[VolumeRoutingError[N, E], Vector[VolumeRoute[N, E]]] =
    if maxPaths <= 0 then Left(VolumeRoutingError.InvalidLimit(maxPaths))
    else if !inversePenalty.isFinite || inversePenalty < 0.0 then Left(VolumeRoutingError.InvalidPenalty(inversePenalty))
    else
      for
        _ <- domain(source)
        _ <- domain(target)
        graph <- routeGraph(if allowInverses then forwardSteps ++ inverseSteps(inversePenalty) else forwardSteps)
        paths <- DirectedRoutes.allSimplePaths(graph, source, target).left.map(_ => VolumeRoutingError.NoPath(source, target))
      yield paths.sortBy(route => (route.map(_.cost).sum, route.length,
        route.map(step => edgeLabel(step.registration.id) + (if step.inverted then ":inverse" else "")).mkString("|")))
        .take(maxPaths).map(steps => new VolumeRoute(source, target, steps))

  private def shortest(source: N, target: N, steps: Vector[VolumeRouteStep[N, E]]): Either[VolumeRoutingError[N, E], Option[VolumeRoute[N, E]]] =
    routeGraph(steps).flatMap: graph =>
      DirectedRoutes.shortest(graph, source, target).left.map(_ => VolumeRoutingError.NoPath(source, target))
        .map(_.map(steps => new VolumeRoute(source, target, steps)))

  private def forwardSteps: Vector[VolumeRouteStep[N, E]] = registrations.map(r => VolumeRouteStep(r, false, r.cost))

  private def inverseSteps(penalty: Double): Vector[VolumeRouteStep[N, E]] = registrations.flatMap: r =>
    r.inverseQuality.filter(_ => r.map.forall(_.inversePullback.nonEmpty)).flatMap: q =>
      val cost = r.cost + penalty * (1.0 - q)
      if cost.isFinite then Some(VolumeRouteStep(r, true, cost)) else None

  private def routeGraph(steps: Vector[VolumeRouteStep[N, E]]): Either[VolumeRoutingError[N, E], WeightedDigraph[N, Vector[RouteChoice[VolumeRouteStep[N, E]]]]] =
    val choices = steps.filter(step => !summon[Hash[N]].eqv(step.source, step.target)).zipWithIndex
    Digraph.of(domains.map(_.id), choices.map((step, _) => ArcInput(step.source, step.target))).toEither
      .left.map(error => VolumeRoutingError.Topology(error.toString))
      .map: topology =>
        WeightedDigraph.from(ArcField.total(topology): arc =>
          choices.collect:
            case (step, order) if summon[Hash[N]].eqv(step.source, arc.source) && summon[Hash[N]].eqv(step.target, arc.target) => RouteChoice(step, step.cost, order)
        )

object VolumeGraph:
  def build[N: Hash, E](domains: Vector[VolumeDomain[N]], registrations: Vector[VolumeRegistration[N, E]]): Either[VolumeRoutingError[N, E], VolumeGraph[N, E]] =
    var nodes = HashMap.empty[N, VolumeDomain[N]]
    var i = 0
    while i < domains.length do
      val node = domains(i)
      if nodes.contains(node.id) then return Left(VolumeRoutingError.DuplicateDomain(node.id))
      nodes = nodes.updated(node.id, node)
      i += 1
    val seen = scala.collection.mutable.HashSet.empty[E]
    i = 0
    while i < registrations.length do
      val r = registrations(i)
      if seen.contains(r.id) then return Left(VolumeRoutingError.DuplicateRegistration(r.id))
      seen += r.id
      val source = nodes.get(r.source) match
        case Some(value) => value
        case None => return Left(VolumeRoutingError.MissingDomain(r.source))
      val target = nodes.get(r.target) match
        case Some(value) => value
        case None => return Left(VolumeRoutingError.MissingDomain(r.target))
      r.map match
        case None => ()
        case Some(binding) =>
          SpatialMap.validateSourceFrame(target.frame, binding.pullback.source) match
            case Left(error) => return Left(VolumeRoutingError.FrameMismatch(error))
            case Right(_) => ()
          SpatialMap.validateResultFrame(source.frame, binding.pullback.target) match
            case Left(error) => return Left(VolumeRoutingError.FrameMismatch(error))
            case Right(_) => ()
      i += 1
    Right(new VolumeGraph(domains, registrations, nodes))
