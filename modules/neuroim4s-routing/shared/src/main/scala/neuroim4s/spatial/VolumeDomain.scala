package neuroim4s.spatial

import image4s.{SampleSpace, SomeSampleSpace}
import image4s.geometry.{D3, Frame, Grid, GeometryError}
import neuroim4s.image.{GridSpec, SampleSpaceError, SampleSpaces, SomeMaskVolume}
import neuroim4s.spaces.{NativeContext, SessionId, SpaceError, SubjectId, TemplateName, WorldSpace}
import neuroim4s.image.SomeNeuroVolume.*

/** Anatomical reference only. Acquisition modality and sampled resolution belong to callers and grids. */
enum VolumeReference:
  case Native(subject: SubjectId, session: Option[SessionId])
  case Template(name: TemplateName)

  def world: Either[SpaceError, WorldSpace] = this match
    case Template(name) => Right(WorldSpace.Template(name))
    case Native(subject, _) => Left(SpaceError.MissingNativeContext(s"subject ${subject.value}'s volume lives in subject-native coordinates"))

  def worldIn(native: NativeContext): Either[SpaceError, WorldSpace] = this match
    case Template(name) => Right(WorldSpace.Template(name))
    case Native(subject, session) =>
      if native.subject != subject then
        Left(SpaceError.ConflictingEvidence(s"domain subject ${subject.value}", s"native context subject ${native.subject.value}"))
      else if session.exists(ses => !native.session.contains(ses)) then
        Left(SpaceError.ConflictingEvidence(s"domain session ${session.fold("")(_.value)}", s"native context session ${native.session.fold("none")(_.value)}"))
      else Right(WorldSpace.SubjectNative(native.namespace, native.subject, native.session, native.reference))

enum VolumeGridError:
  case Admission(cause: SampleSpaceError)
  case Geometry(cause: GeometryError)

/** A D3-admitted provider sample space and optional congruent mask, without a second grid representation. */
final class VolumeGrid private (
    val space: SampleSpace[? <: Frame[D3], D3],
    val mask: Option[SomeMaskVolume]
):
  def frame: Frame[D3] = space.grid.frame
  def size: Int = space.grid.shape.product
  def spec: GridSpec[?] = GridSpec.fromSpace(space.typed.spatialOnly)

  override def equals(other: Any): Boolean = other match
    case that: VolumeGrid => space == that.space && mask == that.mask
    case _ => false
  override def hashCode(): Int = 31 * space.hashCode() + mask.hashCode()
  override def toString: String = s"Volume($space,$mask)"

object VolumeGrid:
  def from(space: SomeSampleSpace, mask: Option[SomeMaskVolume] = None): Either[VolumeGridError, VolumeGrid] =
    for
      admitted <- SampleSpaces.requireVolumeD3(space.typed.spatialOnly).left.map(VolumeGridError.Admission.apply)
      _ <- mask match
        case Some(value) => Grid.exactCongruence(admitted.grid, value.grid).left.map(VolumeGridError.Geometry.apply).map(_ => ())
        case None => Right(())
    yield new VolumeGrid(admitted, mask)

/** Volume routing keys stay caller-owned. Sampled and unsampled nodes share an actual provider frame. */
final class VolumeDomain[N] private (
    val id: N,
    val reference: VolumeReference,
    val frame: Frame[D3],
    val sampling: Option[VolumeGrid]
)

object VolumeDomain:
  def sampled[N](id: N, reference: VolumeReference, grid: VolumeGrid): VolumeDomain[N] =
    new VolumeDomain(id, reference, grid.frame, Some(grid))

  def unsampled[N](id: N, reference: VolumeReference, frame: Frame[D3]): VolumeDomain[N] =
    new VolumeDomain(id, reference, frame, None)
