"""`uiautomator` dumps as nodes, not as lines of grep and sed.

`tests/live/phone.sh` reads the screen by splitting the XML on ``>`` and
matching strings: a node is "the first line that contains ``id/name\\"``" and its
text is "whatever ``text=\\"...\\"`` that line holds". That works because a dump
puts one element on one line, but it also means a resource id inside a
``content-desc`` looks like a node, an XML entity is compared undecoded, and a
malformed dump is silently treated as no match.

This module keeps the selection rules that are contract — first node in
document order, a card's fields are found after its name, a centre is
truncated, not rounded — and parses the document with
:mod:`xml.etree.ElementTree` so the values are the ones the phone meant:

* ``ui_text ID`` and ``ui_bounds ID`` return the first ``node`` whose
  ``resource-id`` names ``ID``; the shell's first *line* could also be a
  non-node with the id in ``content-desc``.
* XML entities are decoded. A label the shell printed as ``&quot;`` is ``"``
  here; that is a difference no caller should depend on.
* Bounds are ``[x1,y1][x2,y2]`` with non-negative integers. The shell's sed
  pattern did not accept a minus sign, so a node with negative bounds had no
  centre and no tap; keep that: :meth:`Node.centre` returns ``None`` for it.
* ``card_line`` remembers the last ``card_name`` node whose text is exactly
  the card's name and returns the first later node with the wanted id. A
  button id shared by every card therefore belongs to the right card.

The module is pure: strings and numbers in, nodes out. ``phone.py`` owns adb.
"""

from __future__ import annotations

import re
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from typing import Iterable, Iterator, Mapping

# The shell's sed pattern, deliberately without a minus sign. Android reports
# non-negative screen coordinates; a negative pair is "no usable node", which
# is what the old helper decided too.
_BOUNDS = re.compile(r"^\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]$")


@dataclass(frozen=True)
class Node:
    """One ``<node>`` of a dump, with the attributes callers ask for."""

    tag: str
    text: str
    resource_id: str
    content_desc: str
    bounds: str
    attrib: Mapping[str, str]
    index: int

    @property
    def package(self) -> str:
        return self.attrib.get("package", "")

    @property
    def class_name(self) -> str:
        return self.attrib.get("class", "")

    def rectangle(self) -> tuple[int, int, int, int] | None:
        """``(x1, y1, x2, y2)``, or ``None`` for malformed/negative bounds."""
        match = _BOUNDS.match(self.bounds)
        if match is None:
            return None
        return tuple(int(value) for value in match.groups())

    def centre(self) -> tuple[int, int] | None:
        """The tap point the shell computed: ``int((x1+x2)/2)``, truncated."""
        rectangle = self.rectangle()
        if rectangle is None:
            return None
        x1, y1, x2, y2 = rectangle
        return int((x1 + x2) / 2), int((y1 + y2) / 2)


class Screen:
    """A parsed dump, queried the way the shell's helpers queried it."""

    def __init__(self, xml: str, root: ET.Element) -> None:
        self.xml = xml
        self.root = root
        self.nodes: list[Node] = []
        for index, element in enumerate(root.iter("node")):
            attrib = dict(element.attrib)
            self.nodes.append(
                Node(
                    tag=element.tag,
                    text=attrib.get("text", ""),
                    resource_id=attrib.get("resource-id", ""),
                    content_desc=attrib.get("content-desc", ""),
                    bounds=attrib.get("bounds", ""),
                    attrib=attrib,
                    index=index,
                )
            )

    @classmethod
    def parse(cls, xml: str) -> "Screen":
        """Parse a dump. Raises :class:`xml.etree.ElementTree.ParseError`."""
        text = xml.replace("\r", "")
        return cls(text, ET.fromstring(text))

    def __iter__(self) -> Iterator[Node]:
        return iter(self.nodes)

    def __len__(self) -> int:
        return len(self.nodes)

    # -- selectors -----------------------------------------------------
    def by_text(self, text: str, *, exact: bool = True) -> list[Node]:
        if exact:
            return [node for node in self.nodes if node.text == text]
        return [node for node in self.nodes if text in node.text]

    def by_content_desc(self, text: str, *, exact: bool = True) -> list[Node]:
        if exact:
            return [node for node in self.nodes if node.content_desc == text]
        return [node for node in self.nodes if text in node.content_desc]

    def by_resource_id(self, wanted: str) -> list[Node]:
        return [
            node
            for node in self.nodes
            if resource_id_matches(node.resource_id, wanted)
        ]

    def first(
        self,
        *,
        text: str | None = None,
        resource_id: str | None = None,
        content_desc: str | None = None,
    ) -> Node | None:
        for node in self.nodes:
            if text is not None and node.text != text:
                continue
            if resource_id is not None and not resource_id_matches(
                node.resource_id, resource_id
            ):
                continue
            if content_desc is not None and node.content_desc != content_desc:
                continue
            return node
        return None

    def has_resource_id(self, wanted: str) -> bool:
        return any(
            resource_id_matches(node.resource_id, wanted) for node in self.nodes
        )

    # -- the four helpers phone.sh had ---------------------------------
    def text(self, resource_id: str) -> str:
        """``ui_text ID``: the first matching node's text, or ``""``."""
        node = self.first(resource_id=resource_id)
        return node.text if node is not None else ""

    def bounds(self, resource_id: str) -> tuple[int, int] | None:
        """``ui_bounds ID``: the matching node's truncated centre."""
        node = self.first(resource_id=resource_id)
        return node.centre() if node is not None else None

    def card_line(self, name: str, resource_id: str) -> Node | None:
        """``card_line NAME ID``: the node after the card called NAME."""
        hit = False
        for node in self.nodes:
            if resource_id_matches(node.resource_id, "card_name"):
                hit = node.text == name
            if hit and resource_id_matches(node.resource_id, resource_id):
                return node
        return None

    def card_text(self, name: str, resource_id: str) -> str:
        node = self.card_line(name, resource_id)
        return node.text if node is not None else ""


def resource_id_matches(actual: str, wanted: str) -> bool:
    """The shell's ``grep "id/NAME\\""``, without the substring surprises.

    ``wanted`` may be a bare name (``header_title``), the form the shell passed
    (``id/header_title``), or the full ``package:id/header_title``.
    """
    if not wanted:
        return False
    if actual == wanted:
        return True
    if wanted.startswith("id/"):
        return actual.endswith(":" + wanted)
    return actual.endswith(":id/" + wanted) or actual.endswith("/" + wanted)


def count_resource_ids(xml: str, package: str) -> int:
    """How many ``package:id/[a-z_]*`` names a dump holds, as grep -o counted."""
    return len(re.findall(re.escape(package) + r":id/[a-z_]*", xml))


def looks_like_panel(xml: str, package: str) -> bool:
    """The acceptance test in ``ui_dump``: a hierarchy with the app in it."""
    if "<hierarchy" not in xml:
        return False
    return (
        f"{package}:id/header_title" in xml
        or 'id/alertTitle"' in xml
        or 'text="About this app"' in xml
    )


def has_package(xml: str, package: str) -> bool:
    """The shell's ``package=\\"...\\"`` test for "the app has the focus"."""
    return f'package="{package}"' in xml


def nodes(xml: str) -> Iterable[Node]:
    """Every node of a dump, in document order."""
    return iter(Screen.parse(xml))
