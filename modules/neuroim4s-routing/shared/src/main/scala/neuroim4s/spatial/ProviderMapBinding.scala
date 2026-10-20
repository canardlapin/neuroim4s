package neuroim4s.spatial

import image4s.geometry.{Affine, D3, Frame, GeometryError}
import neuroim4s.image.SpatialPullback
import neuroim4s.transform.WorldTransform
import reframe4s.core.{FrameErasedMap, SpatialMap}
import reframe4s.field.DenseMap
import scala.util.hashing.MurmurHash3

enum RegistrationMapError:
  case InvalidProviderCoordinateMap(reason: String)
  case CoordinateTransformFailed(reason: String)
  case Geometry(cause: GeometryError)

  def message: String = this match
    case InvalidProviderCoordinateMap(reason) => reason
    case CoordinateTransformFailed(reason) => reason
    case Geometry(cause) => cause.message

opaque type CoordinateMapFingerprint = String

object CoordinateMapFingerprint:
  private[spatial] def unsafe(value: String): CoordinateMapFingerprint =
    value

  extension (fingerprint: CoordinateMapFingerprint)
    def value: String =
      fingerprint

/** Checked storage envelope for the one provider-owned geometric map algebra.
  *
  * This value carries routing metadata and an optional executable inverse. It
  * never evaluates or composes coordinates itself: `pullback` and every
  * composed value are reframe4s `SpatialMap`s.
  */
final case class ProviderMapBinding private (
  pullback: SpatialPullback[Frame[D3], Frame[D3]],
  inversePullback: Option[SpatialPullback[Frame[D3], Frame[D3]]],
  fingerprint: CoordinateMapFingerprint,
  private[spatial] val inverseFingerprint: Option[CoordinateMapFingerprint],
  affineOperator: Option[Affine[D3]],
  containsDense: Boolean,
  componentCount: Int
):
  def isAffine: Boolean =
    affineOperator.nonEmpty

  def inverted: Either[RegistrationMapError, ProviderMapBinding] =
    (inversePullback, inverseFingerprint) match
      case (Some(inverse), Some(reverseFingerprint)) =>
        Right(
          new ProviderMapBinding(
            inverse,
            Some(pullback),
            reverseFingerprint,
            Some(fingerprint),
            affineOperator.map(_.inverse),
            containsDense,
            componentCount
          )
        )
      case (None, None) =>
        Left(RegistrationMapError.CoordinateTransformFailed("provider map has no executable geometric inverse"))
      case _ =>
        Left(RegistrationMapError.InvalidProviderCoordinateMap("provider inverse map and fingerprint must be present together"))

object ProviderMapBinding:
  def affineBetween(
      outputFrame: Frame[D3],
      inputFrame: Frame[D3],
      operator: Affine[D3]
  ): Either[RegistrationMapError, ProviderMapBinding] =
    val pullback = reframe4s.lie.FramedAffine.betweenFrames[Frame[D3], Frame[D3], D3](inputFrame, outputFrame)(operator)
    val inverse = reframe4s.lie.FramedAffine.betweenFrames[Frame[D3], Frame[D3], D3](outputFrame, inputFrame)(operator.inverse)
    affineMaps(pullback, inverse, operator)

  /** Admit a dense provider map into the routing graph.
    *
    * Graph edges join domains whose frames are known only at runtime, so the map is stored in reframe4s' erased form,
    * which re-validates both endpoint owners on every application.
    */
  def dense(
    pullback: SpatialPullback[?, ?],
    inversePullback: Option[SpatialPullback[?, ?]] = None
  ): Either[RegistrationMapError, ProviderMapBinding] =
    denseErased(erase(pullback), inversePullback.map(erase))

  /** Admit a `neuroim4s.transform` world transform as a routing edge's map, stored as its pullback.
    *
    * Affines keep their exact inverse and fuse with neighbouring affines; analytic isomorphisms keep their analytic
    * inverse; dense and composite maps carry a forward direction only when the transform has one (an inverse asset).
    * `identity` must determine the transform's values, e.g. the SHA-256 digests of the assets it was read from plus
    * every policy that changes them; it keys non-affine maps, whose values cannot be fingerprinted structurally.
    */
  def fromWorldTransform[S <: Frame[D3], T <: Frame[D3]](
      transform: WorldTransform[S, T],
      identity: String
  ): Either[RegistrationMapError, ProviderMapBinding] =
    transform match
      case WorldTransform.Linear(framed, _) =>
        affineBetween(framed.target, framed.source, framed.operator)
      case WorldTransform.Smooth(iso, _) =>
        mapped(iso, s"$identity|pull", Some(iso.inverse -> s"$identity|push"), containsDense = false)
      case WorldTransform.Mapped(pull, availability, _) =>
        val push = availability.map
        if DenseMap.isDense(erase(pull)) && push.forall(map => DenseMap.isDense(erase(map))) then dense(pull, push)
        else mapped(pull, s"$identity|pull", push.map(_ -> s"$identity|push"), containsDense = true)

  /** Admit any other provider map (e.g. a toolkit composite) under a caller-derived identity. `identity` must determine
    * the map, e.g. the SHA-256 digests of the assets it was read from plus every policy that changes its values.
    */
  def mapped(
      pullback: SpatialPullback[?, ?],
      identity: String,
      inverse: Option[(SpatialPullback[?, ?], String)],
      containsDense: Boolean
  ): Either[RegistrationMapError, ProviderMapBinding] =
    def fingerprint(value: String): CoordinateMapFingerprint =
      val hash = MurmurHash3.stringHash(s"provider-mapped-v1|$value")
      CoordinateMapFingerprint.unsafe(s"provider-mapped-v1:${java.lang.Integer.toHexString(hash)}")
    if identity.trim.isEmpty || inverse.exists(_._2.trim.isEmpty) then
      Left(RegistrationMapError.InvalidProviderCoordinateMap("a mapped provider binding needs a non-empty identity"))
    else
      ProviderMapBinding
        .mappedErased(erase(pullback), fingerprint(identity), inverse.map((map, id) => erase(map) -> fingerprint(id)), containsDense)

  private def erase[T <: Frame[D3], S <: Frame[D3]](
      map: SpatialPullback[T, S]
  ): SpatialPullback[Frame[D3], Frame[D3]] =
    map match
      case erased: FrameErasedMap[D3 @unchecked] => erased
      case typed => SpatialMap.eraseFrameRefinements(typed)


  private[spatial] def numericFingerprint(prefix: String, values: Array[Double]): String =
    val hash = hashDoubles(MurmurHash3.stringHash(prefix), values)
    s"$prefix:${java.lang.Integer.toHexString(MurmurHash3.finalizeHash(hash, values.length))}"

  private def hashDoubles(seed: Int, values: Array[Double]): Int =
    var hash = seed
    var i = 0
    while i < values.length do
      hash = MurmurHash3.mix(hash, values(i).hashCode)
      i += 1
    hash

  def attachInverse(
      primary: ProviderMapBinding,
      reverse: ProviderMapBinding
  ): Either[RegistrationMapError, ProviderMapBinding] =
    validateInverse(primary.pullback, reverse.pullback).map: _ =>
      new ProviderMapBinding(
        primary.pullback,
        Some(reverse.pullback),
        primary.fingerprint,
        Some(reverse.fingerprint),
        primary.affineOperator,
        primary.containsDense,
        primary.componentCount
      )

  private def affineMaps(
    pullback: SpatialPullback[Frame[D3], Frame[D3]],
    inversePullback: SpatialPullback[Frame[D3], Frame[D3]],
    operator: Affine[D3]
  ): Either[RegistrationMapError, ProviderMapBinding] =
    validateInverse(pullback, inversePullback).map: _ =>
      new ProviderMapBinding(
        pullback,
        Some(inversePullback),
        CoordinateMapFingerprint.unsafe(numericFingerprint("provider-affine-v1", operator.rowMajor.toArray)),
        Some(
          CoordinateMapFingerprint.unsafe(
            numericFingerprint("provider-affine-v1", operator.inverse.rowMajor.toArray)
          )
        ),
        Some(operator),
        containsDense = false,
        componentCount = 1
      )

  private def denseErased(
    pullback: SpatialPullback[Frame[D3], Frame[D3]],
    inversePullback: Option[SpatialPullback[Frame[D3], Frame[D3]]]
  ): Either[RegistrationMapError, ProviderMapBinding] =
    val denseValidation =
      if DenseMap.isDense(pullback) && inversePullback.forall(DenseMap.isDense)
      then Right(())
      else
        Left(
          RegistrationMapError.InvalidProviderCoordinateMap(
            "dense bindings require provider DenseMap values"
          )
        )
    for
      _ <- denseValidation
      _ <- inversePullback.fold[Either[RegistrationMapError, Unit]](Right(()))(validateInverse(pullback, _))
      denseFingerprint <- DenseMap
        .fingerprint(pullback)
        .toRight(RegistrationMapError.InvalidProviderCoordinateMap("provider dense map has no structural fingerprint"))
      inverseDenseFingerprint <- inversePullback match
        case Some(inverse) =>
          DenseMap
            .fingerprint(inverse)
            .map(value => Some(CoordinateMapFingerprint.unsafe(value)))
            .toRight(RegistrationMapError.InvalidProviderCoordinateMap("provider inverse dense map has no structural fingerprint"))
        case None => Right(None)
    yield new ProviderMapBinding(
      pullback,
      inversePullback,
      CoordinateMapFingerprint.unsafe(denseFingerprint),
      inverseDenseFingerprint,
      affineOperator = None,
      containsDense = true,
      componentCount = 1
    )

  /** A provider map that is neither a bare affine nor a bare dense map, e.g. a toolkit composite whose stages reframe4s
    * has already chained. The caller supplies the fingerprint, derived from the map's provenance (asset digests).
    */
  private def mappedErased(
      pullback: SpatialPullback[Frame[D3], Frame[D3]],
      fingerprint: CoordinateMapFingerprint,
      inverse: Option[(SpatialPullback[Frame[D3], Frame[D3]], CoordinateMapFingerprint)],
      containsDense: Boolean
  ): Either[RegistrationMapError, ProviderMapBinding] =
    inverse
      .fold[Either[RegistrationMapError, Unit]](Right(()))((reverse, _) => validateInverse(pullback, reverse))
      .map: _ =>
        new ProviderMapBinding(
          pullback,
          inverse.map(_._1),
          fingerprint,
          inverse.map(_._2),
          affineOperator = None,
          containsDense = containsDense,
          componentCount = 1
        )

  def composed(
      components: Vector[ProviderMapBinding],
      inverseComponents: Option[Vector[ProviderMapBinding]]
  ): Either[RegistrationMapError, ProviderMapBinding] =
    for
      pullback <- composeProvider(components)
      inverse <- inverseComponents match
        case Some(explicit) => composeProvider(explicit).map(Some.apply)
        case None =>
          val derived = components.reverse.map(_.inversePullback)
          if derived.forall(_.nonEmpty) then
            composeProviderMaps(derived.flatten).map(Some.apply)
          else Right(None)
      _ <- inverse.fold[Either[RegistrationMapError, Unit]](Right(()))(validateInverse(pullback, _))
      affine <- composeAffines(components)
      fingerprint = compositeFingerprint(components.map(_.fingerprint))
      inverseFingerprint = inverseComponents match
        case Some(explicit) => Some(compositeFingerprint(explicit.map(_.fingerprint)))
        case None =>
          val derived = components.reverse.map(_.inverseFingerprint)
          if derived.forall(_.nonEmpty) then Some(compositeFingerprint(derived.flatten))
          else None
    yield new ProviderMapBinding(
      pullback,
      inverse,
      fingerprint,
      inverseFingerprint,
      affine,
      components.exists(_.containsDense),
      components.map(_.componentCount).sum
    )

  private def composeProvider(
      components: Vector[ProviderMapBinding]
  ): Either[RegistrationMapError, SpatialPullback[Frame[D3], Frame[D3]]] =
    if components.isEmpty then
      Left(RegistrationMapError.InvalidProviderCoordinateMap("at least one provider map is required"))
    else composeProviderMaps(components.map(_.pullback))

  private def composeProviderMaps(
      components: Vector[SpatialPullback[Frame[D3], Frame[D3]]]
  ): Either[RegistrationMapError, SpatialPullback[Frame[D3], Frame[D3]]] =
    var current = components.head
    var index = 1
    var error = Option.empty[RegistrationMapError]
    while index < components.length && error.isEmpty do
      val next = components(index)
      SpatialMap.validateResultFrame(current.target, next.source) match
        case Left(cause) =>
          error = Some(RegistrationMapError.InvalidProviderCoordinateMap(s"component ${index - 1} -> $index: ${cause.message}"))
        case Right(_) =>
          current = SpatialMap.compose(current, next)
      index += 1
    error.toLeft(current)

  private def composeAffines(
      components: Vector[ProviderMapBinding]
  ): Either[RegistrationMapError, Option[Affine[D3]]] =
    if !components.forall(_.isAffine) then Right(None)
    else
      var current = components.head.affineOperator.get
      var index = 1
      var error = Option.empty[RegistrationMapError]
      while index < components.length && error.isEmpty do
        current.andThen(components(index).affineOperator.get) match
          case Left(cause) => error = Some(RegistrationMapError.Geometry(cause))
          case Right(value) => current = value
        index += 1
      error match
        case Some(cause) => Left(cause)
        case None => Right(Some(current))

  private[spatial] def validateInverse(
      pullback: SpatialPullback[Frame[D3], Frame[D3]],
      inverse: SpatialPullback[Frame[D3], Frame[D3]]
  ): Either[RegistrationMapError, Unit] =
    for
      _ <- SpatialMap
        .validateSourceFrame(inverse.source, pullback.target)
        .left
        .map(error => RegistrationMapError.InvalidProviderCoordinateMap(error.message))
      _ <- SpatialMap
        .validateResultFrame(pullback.source, inverse.target)
        .left
        .map(error => RegistrationMapError.InvalidProviderCoordinateMap(error.message))
    yield ()

  private def compositeFingerprint(
      components: Vector[CoordinateMapFingerprint]
  ): CoordinateMapFingerprint =
    val ordered = components.map(_.value).mkString("|")
    val hash = MurmurHash3.stringHash(s"provider-composite-v1|components=$ordered")
    CoordinateMapFingerprint.unsafe(s"provider-composite-v1:${java.lang.Integer.toHexString(hash)}")

