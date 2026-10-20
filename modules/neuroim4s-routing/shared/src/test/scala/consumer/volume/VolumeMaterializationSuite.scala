package consumer.volume

import gale.linalg.DMat
import image4s.geometry.{Affine, D3}
import neuroim4s.image.SampleSpaces
import neuroim4s.spatial.*
import neuroim4s.spaces.TemplateName
import cats.Hash

class VolumeMaterializationSuite extends munit.FunSuite:
  private given Hash[String] = Hash.fromUniversalHashCode
  private val grid = VolumeGrid.from(SampleSpaces(Vector(4, 1, 1))).toOption.get

  private class Source(
      val grid: VolumeGrid = VolumeMaterializationSuite.this.grid,
      value: (Int, Int) => Double = (row, observation) => 10.0 * observation + row
  ) extends VolumeSource:
    val observations = 3
    var validations = 0
    var requests = Vector.empty[VolumeReadRequest]
    var available = true
    def validate(): Either[VolumeReadError, Unit] =
      validations += 1
      if available then Right(()) else Left(VolumeReadError.Unavailable("fixture unavailable"))
    def read(request: VolumeReadRequest): Either[VolumeReadError, VolumeBlock] =
      requests = requests :+ request
      val builder = DMat.newBuilder(request.rows, request.columns)
      for row <- request.sourceRows.indices; column <- request.observations.indices do
        builder.writeLinear(row * request.columns + column, value(request.sourceRows(row), request.observations(column)))
      Right(VolumeBlock(request, builder.result()))

  test("standalone scoped execution reads only support, preserving target and observation order"):
    val source = new Source
    val domain = VolumeDomain.sampled("a", VolumeReference.Template(TemplateName("fixture").toOption.get), grid)
    val graph = VolumeGraph.build(Vector(domain), Vector.empty[VolumeRegistration[String, String]]).toOption.get
    val compiled = VolumeExecution.compile(graph, "a", "a", Vector(3, 1), SamplingPolicy.Nearest).toOption.get
    val result = compiled.materialize(source, Vector(2, 0)).toOption.get
    assertEquals(source.validations, 1)
    assertEquals(source.requests.map(_.sourceRows), Vector(Vector(1, 3)))
    assertEquals(source.requests.map(_.observations), Vector(Vector(2, 0)))
    assertEquals(Vector.tabulate(result.rows)(r => Vector.tabulate(result.cols)(c => result(r, c))), Vector(Vector(23.0, 3.0), Vector(21.0, 1.0)))
    source.available = false
    assertEquals(compiled.materialize(source, Vector(2, 0)), Left(VolumeExecutionError.Read(VolumeReadError.Unavailable("fixture unavailable"))))
    assertEquals(source.requests.length, 1)

  test("request admission rejects duplicates and bounds before reading"):
    assertEquals(VolumeReadRequest.make(4, 3, Vector(4), Vector(0)), Left(VolumeReadError.IndexOutOfBounds("source row", 4, 4)))
    assertEquals(VolumeReadRequest.make(4, 3, Vector(1, 1), Vector(0)), Left(VolumeReadError.DuplicateIndex("source row", 1)))
    assertEquals(VolumeReadRequest.make(4, 3, Vector(1), Vector(3)), Left(VolumeReadError.IndexOutOfBounds("observation", 3, 3)))
    val source = new Source
    val request = VolumeReadRequest.make(5, 3, Vector(1), Vector(0)).toOption.get
    assertEquals(VolumeMaterialization.readExpanded(source, request), Left(VolumeReadError.RequestMismatch))
    assertEquals(source.requests, Vector.empty)

  test("empty support and compact shape checks do not invent values"):
    val request = VolumeReadRequest.make(4, 3, Vector.empty, Vector(2)).toOption.get
    val result = request.expand(DMat.newBuilder(0, 1).result()).toOption.get
    assertEquals(result.rows, 4)
    assertEquals(result.cols, 1)
    (0 until 4).foreach(row => assertEqualsDouble(result(row, 0), 0.0, 0.0))
    assertEquals(request.expand(DMat.newBuilder(1, 1).result()), Left(VolumeReadError.BlockShapeMismatch(0, 1, 1, 1)))

  test("chained quarter-voxel registrations sample values once, rather than blurring twice"):
    val reference = VolumeReference.Template(TemplateName("sampling-chain").toOption.get)
    val nodes = Vector("a", "b", "c").map(id => VolumeDomain.sampled(id, reference, grid))
    val shift = Affine.fromRowMajor[D3](Vector(
      1.0, 0.0, 0.0, 0.25, 0.0, 1.0, 0.0, 0.0,
      0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0)).toOption.get
    val binding = ProviderMapBinding.affineBetween(grid.frame, grid.frame, shift).toOption.get
    val edges = Vector(("ab", "a", "b"), ("bc", "b", "c")).map: (id, from, onto) =>
      VolumeRegistration.build(id, from, onto, VolumeMapKind.Affine3D, 1.0, Some(1.0), Some(binding)).toOption.get
    val graph = VolumeGraph.build(nodes, edges).toOption.get
    val source = new Source(value = (row, _) => if row == 2 then 1.0 else 0.0)
    val compiled = VolumeExecution.compile(graph, "a", "c", Vector(1)).toOption.get
    val result = compiled.materialize(source, Vector(0)).toOption.get
    assertEqualsDouble(result(0, 0), 0.5, 1e-12)
    assertEquals(source.requests.map(_.sourceRows), Vector(Vector(1, 2)))

  test("a source on a foreign grid refuses before validation or value reads"):
    val domain = VolumeDomain.sampled("a", VolumeReference.Template(TemplateName("grid-owner").toOption.get), grid)
    val graph = VolumeGraph.build(Vector(domain), Vector.empty[VolumeRegistration[String, String]]).toOption.get
    val compiled = VolumeExecution.compile(graph, "a", "a", Vector(0), SamplingPolicy.Nearest).toOption.get
    val foreign = new Source(VolumeGrid.from(SampleSpaces(Vector(4, 1, 1))).toOption.get)
    assert(compiled.materialize(foreign, Vector(0)).left.exists {
      case VolumeExecutionError.Read(VolumeReadError.GridMismatch(_)) => true
      case _ => false
    })
    assertEquals(foreign.validations, 0)
    assertEquals(foreign.requests.length, 0)
