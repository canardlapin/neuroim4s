package external.neuroim.consumer

import image4s.geometry.{Affine, D3}
import neuroim4s.image.NeuroAffineSyntax.*

class NeuroAffineSyntaxSuite extends munit.FunSuite:
  private def right[A](result: Either[?, A]): A =
    result.fold(error => fail(error.toString), identity)

  private def close(actual: Vector[Double], expected: Vector[Double]): Unit =
    assertEquals(actual.length, expected.length)
    actual.zip(expected).foreach: (a, e) =>
      assertEqualsDouble(a, e, 1e-12)

  private val reflected = right(Affine.fromRowMajor[D3](Vector(
    2.0, 0.0, 0.0, 10.0,
    0.0, -3.0, 0.0, 20.0,
    0.0, 0.0, 4.0, 30.0,
    0.0, 0.0, 0.0, 1.0
  )))

  test("voxel sizes and obliquity match an analytic rotated anisotropic grid"):
    // A 3-4-5 rotation with spacings 3, 4, 5; translation is irrelevant.
    val affine = right(Affine.fromRowMajor[D3](Vector(
      1.8, -3.2, 0.0, 17.0,
      2.4, 2.4, 0.0, -9.0,
      0.0, 0.0, 5.0, 6.0,
      0.0, 0.0, 0.0, 1.0
    )))
    close(affine.neuroVoxelSizes, Vector(3.0, 4.0, 5.0))
    close(affine.neuroObliquity, Vector(math.acos(0.8), math.acos(0.8), 0.0))
    close(reflected.neuroObliquity, Vector(0.0, 0.0, 0.0))

  test("even shapes retain the lower central voxel, rather than geometric midpoint"):
    // Source voxel (1,2,3) is world (12,14,42). Target voxel (0,1,1)
    // must have that same position after changing shape and spacing.
    val rescaled = right(reflected.rescaledVoxelGeometry(
      Vector(4, 6, 8), Vector(4.0, 6.0, 8.0), Some(Vector(2, 3, 4))
    ))
    close(rescaled.rowMajor, Vector(
      4.0, 0.0, 0.0, 12.0,
      0.0, -6.0, 0.0, 20.0,
      0.0, 0.0, 8.0, 34.0,
      0.0, 0.0, 0.0, 1.0
    ))
    close(right(rescaled(Vector(0.0, 1.0, 1.0))), Vector(12.0, 14.0, 42.0))

  test("odd shapes preserve the central voxel with explicit or default target shape"):
    val rescaled = right(reflected.rescaledVoxelGeometry(
      Vector(5, 7, 9), Vector(1.0, 1.5, 2.0), Some(Vector(3, 5, 7))
    ))
    close(rescaled.rowMajor, Vector(
      1.0, 0.0, 0.0, 13.0,
      0.0, -1.5, 0.0, 14.0,
      0.0, 0.0, 2.0, 40.0,
      0.0, 0.0, 0.0, 1.0
    ))
    val defaultShape = right(reflected.rescaledVoxelGeometry(
      Vector(5, 7, 9), Vector(1.0, 1.5, 2.0)
    ))
    close(right(defaultShape(Vector(2.0, 3.0, 4.0))), Vector(14.0, 11.0, 46.0))

  test("identity rescaling leaves all affine coefficients unchanged"):
    val before = reflected.rowMajor
    val rescaled = right(reflected.rescaledVoxelGeometry(
      Vector(4, 7, 8), Vector(2.0, 3.0, 4.0)
    ))
    close(rescaled.rowMajor, before)
    close(reflected.rowMajor, before)

  test("invalid shape and spacing are rejected at the existing boundary"):
    intercept[IllegalArgumentException]:
      reflected.rescaledVoxelGeometry(Vector(4, 6), Vector(1.0, 1.0, 1.0))
    intercept[IllegalArgumentException]:
      reflected.rescaledVoxelGeometry(Vector(4, 0, 8), Vector(1.0, 1.0, 1.0))
    intercept[IllegalArgumentException]:
      reflected.rescaledVoxelGeometry(Vector(4, 6, 8), Vector(1.0, Double.NaN, 1.0))
    intercept[IllegalArgumentException]:
      reflected.rescaledVoxelGeometry(Vector(4, 6, 8), Vector(1.0, -1.0, 1.0))
    intercept[IllegalArgumentException]:
      reflected.rescaledVoxelGeometry(Vector(4, 6, 8), Vector(1.0, 1.0, 1.0), Some(Vector(0, 3, 4)))
