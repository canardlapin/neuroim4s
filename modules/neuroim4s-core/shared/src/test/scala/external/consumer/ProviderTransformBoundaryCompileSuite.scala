package external.consumer

import scala.compiletime.testing.typeCheckErrors

class ProviderTransformBoundaryCompileSuite extends munit.FunSuite:
  test("the removed image transform hierarchy cannot be imported"):
    val removed = Vector(
      "SpatialMorphism" ->
        typeCheckErrors("import neuroim4s.image.SpatialMorphism"),
      "Affine3DMorphism" ->
        typeCheckErrors("import neuroim4s.image.Affine3DMorphism"),
      "IdentityMorphism" ->
        typeCheckErrors("import neuroim4s.image.IdentityMorphism"),
      "DenseFieldMorphism" ->
        typeCheckErrors("import neuroim4s.image.DenseFieldMorphism"),
      "SpatialDomainId" ->
        typeCheckErrors("import neuroim4s.image.SpatialDomainId"),
      "MorphismExecutionPlan" ->
        typeCheckErrors("import neuroim4s.image.MorphismExecutionPlan"),
      "DenseFieldInterpolationPlan" ->
        typeCheckErrors("import neuroim4s.image.DenseFieldInterpolationPlan"),
      "DenseFieldInverse" ->
        typeCheckErrors("import neuroim4s.image.DenseFieldInverse"),
      "JacobianField" ->
        typeCheckErrors("import neuroim4s.image.JacobianField"),
      "JacobianMode" ->
        typeCheckErrors("import neuroim4s.image.JacobianMode"),
      "MorphismFields" ->
        typeCheckErrors("import neuroim4s.image.MorphismFields")
    )

    removed.foreach: (name, errors) =>
      assert(
        errors.nonEmpty,
        clue = s"removed transform API returned: $name"
      )

  test("the image boundary consumes the provider spatial-map contract"):
    val errors = typeCheckErrors(
      """
        import image4s.geometry.{D3, Frame}
        import reframe4s.core.SpatialMap
        import neuroim4s.image.{SpatialPullback, SpatialPullbacks}

        def consume[T <: Frame[D3], S <: Frame[D3]](map: SpatialPullback[T, S]): SpatialMap[T, S, D3] =
          map

        def applyAt(
          map: SpatialPullback[?, ?],
          point: neuroim4s.image.SpatialPoint
        ) = SpatialPullbacks.transform(map, point)
      """
    )

    assertEquals(errors, Nil)
