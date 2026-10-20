package external.consumer

import image4s.SomeSampleSpace

import scala.compiletime.testing.typeCheckErrors

final class UnsafeSpaceRefinementCompileSuite extends munit.FunSuite:

  test("deleted image-dimension wrapper names are absent from the public API"):
    val imageDimErrors =
      typeCheckErrors("import neuroim4s.image.ImageDim")
    val sliceErrors =
      typeCheckErrors("import neuroim4s.image.Slice2D")
    val volumeErrors =
      typeCheckErrors("import neuroim4s.image.Volume3D")
    val seriesErrors =
      typeCheckErrors("import neuroim4s.image.Series4D")
    val evidenceErrors =
      typeCheckErrors("import neuroim4s.image.ImageDimEvidence")
    val imageSpaceErrors =
      typeCheckErrors("import neuroim4s.image.ImageSpace")

    assert(imageDimErrors.nonEmpty)
    assert(sliceErrors.nonEmpty)
    assert(volumeErrors.nonEmpty)
    assert(seriesErrors.nonEmpty)
    assert(evidenceErrors.nonEmpty)
    assert(imageSpaceErrors.nonEmpty)

  test("exact provider views retain S while owner-erased views expose only SomeSampleSpace"):
    val errors = typeCheckErrors(
      """
import image4s.*
import image4s.geometry.*
import ravel.Rank
import neuroim4s.image.*

def exactVolumeProvider[S <: SampleSpace[?, D3], A, Sem](
    volume: NeuroVolume[S, A, Sem]
): Sampled[S, A, Sem, Rank[3]] =
  volume.sampled

def exactVolumeSampleSpace[S <: SampleSpace[?, D3], A, Sem](
    volume: NeuroVolume[S, A, Sem]
): S =
  volume.sampled.sampleSpace

def exactSeriesProvider[S <: SampleSpace[?, D3], A, Sem](
    series: NeuroSeries[S, A, Sem]
): Sampled[S, A, Sem, Rank[4]] =
  series.sampled

def exactSeriesSampleSpace[S <: SampleSpace[?, D3], A, Sem](
    series: NeuroSeries[S, A, Sem]
): S =
  series.sampled.sampleSpace

def erasedVolumeSampleSpace[A, Sem](
    volume: SomeNeuroVolume[A, Sem]
): SomeSampleSpace =
  volume.sampleSpace

def erasedSeriesSampleSpace[A, Sem](
    series: SomeNeuroSeries[A, Sem]
): SomeSampleSpace =
  series.sampleSpace
"""
    )

    assertEquals(errors, Nil)

  test("owner-erased views cannot recover S or call the deleted typedSpace wrapper"):
    val recoverVolumeOwnerErrors = typeCheckErrors(
      """
import image4s.*
import image4s.geometry.*
import neuroim4s.image.*

def recover[S <: SampleSpace[?, D3], A, Sem](
    volume: SomeNeuroVolume[A, Sem]
): S = volume.sampleSpace
"""
    )
    val recoverSeriesOwnerErrors = typeCheckErrors(
      """
import image4s.*
import image4s.geometry.*
import neuroim4s.image.*

def recover[S <: SampleSpace[?, D3], A, Sem](
    series: SomeNeuroSeries[A, Sem]
): S = series.sampleSpace
"""
    )
    val volumeTypedSpaceErrors = typeCheckErrors(
      """
import neuroim4s.image.*

def dimension[A, Sem](volume: SomeNeuroVolume[A, Sem]) =
  volume.typedSpace
"""
    )
    val seriesTypedSpaceErrors = typeCheckErrors(
      """
import neuroim4s.image.*

def dimension[A, Sem](series: SomeNeuroSeries[A, Sem]) =
  series.typedSpace
"""
    )

    assert(recoverVolumeOwnerErrors.nonEmpty)
    assert(recoverSeriesOwnerErrors.nonEmpty)
    assert(volumeTypedSpaceErrors.nonEmpty)
    assert(seriesTypedSpaceErrors.nonEmpty)

  test("external consumers cannot forge volume and series space refinements"):
    val volumeErrors = typeCheckErrors(
      """
import neuroim4s.image.*

def forgeVolume(space: SomeSampleSpace): VolumeSpace =
  VolumeSpace.unsafe(space)
"""
    )
    val seriesErrors = typeCheckErrors(
      """
import neuroim4s.image.*

def forgeSeries(space: SomeSampleSpace): SeriesSpace =
  SeriesSpace.unsafe(space)
"""
    )

    assert(volumeErrors.nonEmpty)
    assert(seriesErrors.nonEmpty)
