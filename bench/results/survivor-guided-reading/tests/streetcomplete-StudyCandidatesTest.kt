package de.westnordost.streetcomplete.study

import de.westnordost.streetcomplete.data.elementfilter.toElementFilterExpression
import de.westnordost.streetcomplete.data.osm.edits.update_tags.StringMapEntryModify
import de.westnordost.streetcomplete.data.osm.mapdata.LatLon
import de.westnordost.streetcomplete.osm.Sides
import de.westnordost.streetcomplete.osm.Tags
import de.westnordost.streetcomplete.osm.estimateBuildingHeight
import de.westnordost.streetcomplete.osm.estimateMinBuildingHeight
import de.westnordost.streetcomplete.osm.isChildOf
import de.westnordost.streetcomplete.osm.street_parking.ParkingOrientation
import de.westnordost.streetcomplete.osm.street_parking.ParkingPosition
import de.westnordost.streetcomplete.osm.street_parking.StreetParking
import de.westnordost.streetcomplete.osm.street_parking.applyTo
import de.westnordost.streetcomplete.osm.street_parking.parseStreetParkingSides
import de.westnordost.streetcomplete.osm.updateWithCheckDate
import de.westnordost.streetcomplete.quests.barrier_type.AddBarrierOnRoad
import de.westnordost.streetcomplete.testutils.TestMapDataWithGeometry
import de.westnordost.streetcomplete.testutils.bbox
import de.westnordost.streetcomplete.testutils.feature
import de.westnordost.streetcomplete.testutils.node
import de.westnordost.streetcomplete.testutils.p
import de.westnordost.streetcomplete.testutils.way
import de.westnordost.streetcomplete.util.math.intersectsWith
import de.westnordost.streetcomplete.util.math.isCompletelyInside
import de.westnordost.streetcomplete.util.math.isInPolygon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** One test per pooled candidate; a failure means the candidate's claim holds on main. */
class StudyCandidatesTest {
    @Test fun `S1 a height with a unit does not throw`() {
        estimateBuildingHeight(mapOf("building" to "yes", "height" to "10 m", "building:levels" to "2"))
        estimateMinBuildingHeight(mapOf("min_height" to "3 m"))
    }

    @Test fun `S2 changing a value adds no per-key check date when only an element check date exists`() {
        val tags = Tags(mapOf("key" to "old", "check_date" to "2000-01-01"))
        tags.updateWithCheckDate("key", "new")
        assertEquals(setOf(StringMapEntryModify("key", "old", "new")), tags.create().changes.toSet())
    }

    @Test fun `S3 a negated filter matches an untagged node`() {
        assertTrue("nodes with !(shop or craft)".toElementFilterExpression().matches(node(tags = emptyMap())))
    }

    @Test fun `S4 shop car_parts is not a child of shop car`() {
        assertFalse(feature(id = "shop/car_parts").isChildOf(feature(id = "shop/car")))
    }

    @Test fun `S5 a point level with a top edge or bottom corner is outside`() {
        val square = listOf(LatLon(0.0, 0.0), LatLon(0.0, 1.0), LatLon(1.0, 1.0), LatLon(1.0, 0.0), LatLon(0.0, 0.0))
        assertFalse(LatLon(1.0, -1.0).isInPolygon(square))
        val rhombus = listOf(LatLon(1.0, 0.0), LatLon(0.0, 1.0), LatLon(-1.0, 0.0), LatLon(0.0, -1.0), LatLon(1.0, 0.0))
        assertFalse(LatLon(-1.0, -2.0).isInPolygon(rhombus))
    }

    @Test fun `S6 a road crossing a closed wall at its closing node counts`() {
        val shared = node(2, p(0.0, 0.0))
        val mapData = TestMapDataWithGeometry(listOf(
            node(1, p(0.0, -1.0)),
            shared,
            node(3, p(0.0, +1.0)),
            node(4, p(-1.0, 0.0)),
            node(5, p(+1.0, 0.0)),
            node(6, p(5.0, 5.0)),
            way(1, nodes = listOf(1, 2, 3), tags = mapOf("highway" to "unclassified")),
            way(2, nodes = listOf(2, 5, 6, 4, 2), tags = mapOf("barrier" to "wall")),
        ))
        assertEquals(shared, AddBarrierOnRoad().getApplicableElements(mapData).toList().singleOrNull())
    }

    @Test fun `S7 a crossing counts even when the segment also touches the other line's end`() {
        val h = listOf(LatLon(0.0, -1.0), LatLon(0.0, 1.0))
        val v = listOf(LatLon(-1.0, 0.0), LatLon(1.0, 0.0), LatLon(0.0, 1.0))
        assertTrue(h.intersectsWith(v))
    }

    @Test fun `S8 painted area only changed to staggered on street reads back as staggered on street`() {
        val tags = Tags(mapOf(
            "parking:both" to "lane",
            "parking:both:orientation" to "parallel",
            "parking:both:markings" to "yes",
            "parking:both:staggered" to "yes",
        ))
        val answer = StreetParking.PositionAndOrientation(ParkingOrientation.PARALLEL, ParkingPosition.STAGGERED_ON_STREET)
        Sides<StreetParking>(answer, answer).applyTo(tags)
        assertEquals(answer, parseStreetParkingSides(tags.toMap())?.left)
    }

    @Test fun `S9 a box crossing the 180th meridian is not completely inside a box covering only one side`() {
        assertFalse(bbox(0.0, 179.0, 1.0, -179.0).isCompletelyInside(bbox(0.0, -180.0, 1.0, 10.0)))
    }

    @Test fun `S10 today followed by and parses`() {
        "nodes with check_date < today and foo".toElementFilterExpression()
    }
}
