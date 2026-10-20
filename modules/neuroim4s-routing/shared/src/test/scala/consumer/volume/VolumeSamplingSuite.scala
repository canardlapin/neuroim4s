package consumer.volume

import image4s.geometry.{Affine, D3}
import neuroim4s.image.{GridSpec, SampleSpaces, SpatialPoint}
import neuroim4s.spatial.{SamplingPolicy, VolumeSampling, VolumeSamplingError}

class VolumeSamplingSuite extends munit.FunSuite:
  private def grid(size: Int) = GridSpec.fromSpace(SampleSpaces(Vector(size, 1, 1), affine = Some(Affine.identity[D3])))

  test("linear interpolation and partial boundary coverage match analytic weights"):
    val interior = VolumeSampling.assemble(grid(3), grid(1), Vector(0), _ => Right(SpatialPoint(0.25, 0.0, 0.0)), SamplingPolicy.Trilinear).toOption.get
    assertEqualsDouble(interior.map(0, 0), 0.75, 1e-12)
    assertEqualsDouble(interior.map(0, 1), 0.25, 1e-12)
    assertEqualsDouble(interior.coverage.head, 1.0, 1e-12)
    val boundary = VolumeSampling.assemble(grid(3), grid(1), Vector(0), _ => Right(SpatialPoint(-0.25, 0.0, 0.0)), SamplingPolicy.Trilinear).toOption.get
    assertEqualsDouble(boundary.map(0, 0), 1.0, 1e-12)
    assertEqualsDouble(boundary.coverage.head, 0.75, 1e-12)

  test("selected rows keep caller order and evaluate one pullback per target"):
    var evaluations = 0
    def pull(point: SpatialPoint): Either[Nothing, SpatialPoint] =
      evaluations += 1
      Right(point)
    val sampled = VolumeSampling.assemble(grid(3), grid(3), Vector(2, 0), pull, SamplingPolicy.Nearest).toOption.get
    assertEquals(evaluations, 2)
    assertEquals(sampled.map.rows, 2)
    assertEquals(sampled.map.cols, 3)
    assertEqualsDouble(sampled.map(0, 2), 1.0, 0.0)
    assertEqualsDouble(sampled.map(1, 0), 1.0, 0.0)

  test("invalid rows refuse before pullback evaluation and caller errors retain their type"):
    var evaluations = 0
    def pull(point: SpatialPoint): Either[Nothing, SpatialPoint] =
      evaluations += 1
      Right(point)
    val bad = VolumeSampling.assemble(grid(3), grid(3), Vector(3), pull, SamplingPolicy.Nearest)
    assert(bad.left.exists(_.isInstanceOf[VolumeSamplingError.InvalidRows]))
    assertEquals(evaluations, 0)
    enum Refusal:
      case MissingInverse
    val refusal = VolumeSampling.assemble(grid(3), grid(1), Vector(0), _ => Left(Refusal.MissingInverse), SamplingPolicy.Nearest)
    assertEquals(refusal, Left(VolumeSamplingError.Pullback(Refusal.MissingInverse)))
