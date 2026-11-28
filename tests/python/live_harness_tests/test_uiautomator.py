"""The parsed dump: nodes, selectors, card scoping and the shell's quirks."""

from __future__ import annotations

import unittest
import xml.etree.ElementTree as ET

from . import fixtures, support
from harness import uiautomator


class UiautomatorTest(support.Case):
    def test_nodes_by_text_resource_id_and_desc(self) -> None:
        screen = uiautomator.Screen.parse(fixtures.PANEL_XML)
        self.same(len(screen.by_text("Proxies")), 1, "a node is found by text")
        self.same(
            screen.by_text("Proxies")[0].resource_id,
            f"{fixtures.RES}:id/header_title",
            "and keeps its resource id",
        )
        self.same(
            screen.by_resource_id("header_title")[0].text,
            "Proxies",
            "a bare resource id finds the node",
        )
        self.same(
            screen.by_resource_id("id/new_profile")[0].text,
            "New proxy",
            "the shell's id/NAME form finds it too",
        )
        self.same(
            screen.by_resource_id(f"{fixtures.RES}:id/home_scroll")[0].tag,
            "node",
            "and a full resource id",
        )
        self.same(
            screen.by_content_desc("new profile")[0].text,
            "New proxy",
            "content-desc is a selector",
        )
        self.same(
            screen.first(text="Proxies").class_name,
            "android.widget.TextView",
            "class is exposed on the node",
        )
        self.same(screen.text("header_title"), "Proxies", "ui_text is the node's text")
        self.same(screen.text("missing"), "", "a missing node has no text")
        self.same(screen.bounds("missing"), None, "and no centre")

    def test_centre_is_truncated_not_rounded(self) -> None:
        xml = (
            '<hierarchy><node text="" resource-id="p:id/odd" '
            'class="android.widget.Button" package="p" content-desc="" '
            'bounds="[0,0][1,1]" /></hierarchy>'
        )
        screen = uiautomator.Screen.parse(xml)
        self.same(screen.bounds("odd"), (0, 0), "0.5 is truncated to 0")
        self.same(
            uiautomator.Screen.parse(fixtures.PANEL_XML).bounds("new_profile"),
            (980, 2260),
            "a real centre is (x1+x2)/2, (y1+y2)/2",
        )

    def test_negative_bounds_have_no_centre(self) -> None:
        node = uiautomator.Node(
            tag="node",
            text="",
            resource_id="p:id/off",
            content_desc="",
            bounds="[-1,-1][0,0]",
            attrib={},
            index=0,
        )
        self.same(node.rectangle(), None, "the shell's sed did not accept a minus")
        self.same(node.centre(), None, "so there was no tap point")
        malformed = uiautomator.Node(
            tag="node",
            text="",
            resource_id="",
            content_desc="",
            bounds="nonsense",
            attrib={},
            index=0,
        )
        self.same(malformed.centre(), None, "malformed bounds have no centre")

    def test_duplicate_ids_take_the_first_in_document_order(self) -> None:
        xml = (
            '<hierarchy>'
            '<node text="first" resource-id="p:id/x" bounds="[0,0][10,10]"/>'
            '<node text="second" resource-id="p:id/x" bounds="[20,20][30,30]"/>'
            "</hierarchy>"
        )
        screen = uiautomator.Screen.parse(xml)
        self.same(screen.text("x"), "first", "the first node wins, as grep|head did")
        self.same(screen.bounds("x"), (5, 5), "and its bounds are the ones tapped")
        self.same(len(screen.by_resource_id("x")), 2, "both are still exposed")

    def test_card_line_scopes_by_the_card_name(self) -> None:
        screen = uiautomator.Screen.parse(fixtures.CARDS_XML)
        self.same(
            screen.card_line("alpha", "card_toggle").text,
            "Stop",
            "alpha's own button is found",
        )
        self.same(
            screen.card_line("beta", "card_toggle").text,
            "Start",
            "beta's button is not alpha's",
        )
        self.same(
            screen.card_text("beta", "card_state"),
            "Stopped",
            "the state word follows the same card",
        )
        self.same(
            screen.card_line("missing", "card_toggle"),
            None,
            "a name no card holds finds nothing",
        )
        self.same(
            screen.card_text("alpha", "card_toggle"),
            "Stop",
            "card_text is card_line's text",
        )

    def test_card_name_resets_the_hit_on_a_later_card(self) -> None:
        xml = (
            '<hierarchy>'
            '<node text="alpha" resource-id="p:id/card_name"/>'
            '<node text="beta" resource-id="p:id/card_name"/>'
            '<node text="Start" resource-id="p:id/card_toggle"/>'
            "</hierarchy>"
        )
        screen = uiautomator.Screen.parse(xml)
        self.same(
            screen.card_line("alpha", "card_toggle"),
            None,
            "a card after alpha clears alpha's hit",
        )
        self.same(
            screen.card_line("beta", "card_toggle").text,
            "Start",
            "and the later card owns the button",
        )

    def test_entities_are_decoded(self) -> None:
        screen = uiautomator.Screen.parse(fixtures.QUOTE_XML)
        self.same(
            screen.text("message"),
            'Go "now"',
            "XML entities are decoded, unlike the shell's raw sed",
        )

    def test_malformed_xml_raises(self) -> None:
        with self.assertRaises(ET.ParseError):
            uiautomator.Screen.parse("<hierarchy><node></hierarchy>")
        self.checks += 1

    def test_panel_acceptance_and_package(self) -> None:
        self.true(
            uiautomator.looks_like_panel(fixtures.PANEL_XML, fixtures.RES),
            "a header title accepts the dump",
        )
        self.true(
            uiautomator.looks_like_panel(fixtures.ALERT_XML, fixtures.RES),
            "the app's own alert is accepted",
        )
        self.false(
            uiautomator.looks_like_panel(fixtures.BAD_XML, fixtures.RES),
            "a toast alone is not accepted",
        )
        self.false(
            uiautomator.looks_like_panel(fixtures.NO_HIERARCHY, fixtures.RES),
            "text that is not a hierarchy is not accepted",
        )
        self.true(
            uiautomator.has_package(fixtures.PANEL_XML, fixtures.PACKAGE),
            "the app package is seen",
        )
        self.false(
            uiautomator.has_package(fixtures.BAD_XML, fixtures.PACKAGE),
            "a foreign package is not",
        )
        self.same(
            uiautomator.count_resource_ids(fixtures.PANEL_XML, fixtures.RES),
            3,
            "panel_where counts the app's resource ids",
        )

    def test_resource_id_forms(self) -> None:
        full = f"{fixtures.RES}:id/header_title"
        self.true(uiautomator.resource_id_matches(full, "header_title"), "bare")
        self.true(uiautomator.resource_id_matches(full, "id/header_title"), "id/")
        self.true(uiautomator.resource_id_matches(full, full), "full")
        self.false(uiautomator.resource_id_matches(full, "title"), "not a suffix")
        self.false(uiautomator.resource_id_matches(full, ""), "empty never matches")


if __name__ == "__main__":
    unittest.main()
