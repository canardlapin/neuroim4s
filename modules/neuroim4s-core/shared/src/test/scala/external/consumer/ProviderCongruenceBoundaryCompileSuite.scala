package external.consumer

import scala.compiletime.testing.typeCheckErrors

final class ProviderCongruenceBoundaryCompileSuite extends munit.FunSuite:

  test("deleted ScalaFIM congruence algebra cannot be imported"):
    val compatibilityErrors =
      typeCheckErrors("import neuroim4s.image.GridCompatibility")
    val mismatchErrors =
      typeCheckErrors("import neuroim4s.image.GridMismatch")
    val certificateErrors =
      typeCheckErrors("import neuroim4s.image.CertifiedGridCongruence")
    val gridErrorErrors =
      typeCheckErrors(
        "import neuroim4s.image.NeuroImageError; val error = NeuroImageError.Grid"
      )

    assert(compatibilityErrors.nonEmpty)
    assert(mismatchErrors.nonEmpty)
    assert(certificateErrors.nonEmpty)
    assert(gridErrorErrors.nonEmpty)

  test("external consumers use provider congruence and typed image errors directly"):
    val errors = typeCheckErrors(
      """
import image4s.*
import image4s.geometry.*
import neuroim4s.image.NeuroImageError

def exactSampling[LF <: Frame[D3], RF <: Frame[D3]](
    left: SampleSpace[LF, D3],
    right: SampleSpace[RF, D3]
) = SamplingAlignment.exact(left, right)

def approximateSampling[LF <: Frame[D3], RF <: Frame[D3]](
    left: SampleSpace[LF, D3],
    right: SampleSpace[RF, D3],
    tolerance: Double
) = ApproximateSamplingCongruence.check(left, right, tolerance)

def exactGrid[LF <: Frame[D3], RF <: Frame[D3]](
    left: Grid[LF, D3],
    right: Grid[RF, D3]
) = Grid.exactCongruence(left, right)

def approximateGrid[LF <: Frame[D3], RF <: Frame[D3]](
    left: Grid[LF, D3],
    right: Grid[RF, D3],
    tolerance: Double
) = Grid.approximateCongruence(left, right, tolerance)

def imageCause(error: ImageError): NeuroImageError =
  NeuroImageError.Image(error)

def geometryCause(error: GeometryError): NeuroImageError =
  NeuroImageError.Geometry(error)
"""
    )

    assertEquals(errors, Nil)

  test("provider sampling evidence cannot be substituted for a third endpoint"):
    val errors = typeCheckErrors(
      """
import image4s.*
import image4s.geometry.*

def substituteThird[
    LF <: Frame[D3],
    RF <: Frame[D3],
    TF <: Frame[D3]
](
    left: SampleSpace[LF, D3],
    right: SampleSpace[RF, D3],
    third: SampleSpace[TF, D3],
    evidence: SamplingAlignment[left.type, right.type]
): SamplingAlignment[left.type, third.type] =
  evidence
"""
    )

    assert(errors.nonEmpty)
