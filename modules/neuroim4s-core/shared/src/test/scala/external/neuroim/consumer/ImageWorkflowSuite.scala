package external.neuroim.consumer

import image4s.{Axis, AxisKind, Continuous, NonSpatialAxes, SampleSpace, Sampled}
import image4s.geometry.{Affine, D3, Frame, FrameId, Grid, GridId, LengthUnit, CoordinateConvention}
import image4s.locus.GridDomain
import locus4s.{DomainRegistry, Selection}
import neuroim4s.image.*
import ravel.DType.given
import ravel.NDArray

class ImageWorkflowSuite extends munit.FunSuite:
  private def right[A](result: Either[?, A]): A =
    result.fold(error => fail(error.toString), identity)

  test("an independent consumer retains provider owners from series through selected storage and dense output"):
    val frame = right(Frame.persistentNamed[D3](right(FrameId.parse("neuroim4s-consumer")), "consumer", LengthUnit.Millimeter, CoordinateConvention.RAS))
    val grid = right(Grid.createPersistent(right(GridId.parse("neuroim4s-consumer-grid")), frame)(Vector(2, 1, 2), Affine.identity[D3]))
    val time = right(Axis.create("time", 3, AxisKind.Time))
    val axes = right(NonSpatialAxes.from(Vector(time)))
    val sampling = SampleSpace.create(grid, axes)
    val raw = right(Sampled.continuous(sampling,
      NDArray.tabulate[Double](2, 1, 2, 3)((x, _, z, t) => 10.0 * x + z + 100.0 * t)))
    val exact = right(NeuroSeries.fromSampled(raw))
    assert(exact.sampled eq raw)
    val dynamic = SomeNeuroSeries.eraseSpace(exact)
    assert(dynamic.sampled eq raw)
    val flipped = right(exact.flipSeries(2))
    assert(!flipped.data.isCanonicalLayout)
    assertEquals(Vector.tabulate(12)(flipped.valueAtCanonicalOrdinal),
      Vector(1.0, 101.0, 201.0, 0.0, 100.0, 200.0, 11.0, 111.0, 211.0, 10.0, 110.0, 210.0))
    assertEqualsDouble(flipped.valueAtVoxelOrdinal(2, 1), 111.0, 0.0)
    val frameAtOne = right(flipped.volumeAt(1))
    assertEquals(Vector.tabulate(4)(frameAtOne.valueAtCanonicalOrdinal), Vector(101.0, 100.0, 111.0, 110.0))
    intercept[IllegalArgumentException](flipped.valueAtCanonicalOrdinal(-1))
    intercept[IllegalArgumentException](flipped.valueAtCanonicalOrdinal(12))
    intercept[IllegalArgumentException](frameAtOne.valueAtCanonicalOrdinal(4))
    val sequence = NeuroSeriesSeq(Vector(flipped, flipped))
    assertEquals(Vector.tabulate(6)(sequence.valueAtCanonicalOrdinal), Vector(1.0, 101.0, 201.0, 1.0, 101.0, 201.0))
    intercept[IllegalArgumentException](sequence.valueAtCanonicalOrdinal(24))
    val packed = right(GridDomain.register(grid, "consumer voxels", DomainRegistry.empty))
    val domain = packed.value
    val selection = right(Selection.fromOrdinals(domain.space, Vector(3, 0)))
    val selected = right(SelectedSeries.gather(domain, dynamic, selection))
    assert(selected.domain eq domain)
    assert(selected.selection eq selection)
    assertEquals(selected.data.iterator.toVector, Vector(11.0, 111.0, 211.0, 0.0, 100.0, 200.0))
    val dense = right(selected.toDense(Double.NaN))
    assert(dense.grid eq grid)
    assertEqualsDouble(dense(1, 0, 1, 2), 211.0, 0.0)
    assertEqualsDouble(dense(0, 0, 0, 1), 100.0, 0.0)
    assert(dense(0, 0, 1, 1).isNaN)
    val channelSpace = SampleSpace.create(grid,
      right(NonSpatialAxes.from(Vector(right(Axis.create("channel", 3, AxisKind.Channel))))))
    val channel = right(Sampled.continuous(channelSpace, raw.data))
    NeuroSeries.fromSampled(channel) match
      case Left(NeuroImageError.ExpectedSingleTimeAxis(actual)) => assertEquals(actual, Vector(AxisKind.Channel))
      case other => fail(s"expected Time-axis refusal, found $other")
