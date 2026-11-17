"""Offline artifact content check; browser visibility/functionality needs browser evidence."""
from __future__ import annotations

import argparse
import json
from html.parser import HTMLParser
from pathlib import Path

from payload_gate import gate_payload
from render_ops_shell import render_ops_shell


class Node:
    def __init__(self, tag='', attrs=()):
        self.tag = tag
        self.attrs = dict(attrs)
        self.children = []

    def text(self):
        return ''.join(c.text() if isinstance(c, Node) else c for c in self.children)

    def find(self, tag):
        return [c for c in self.walk() if c.tag == tag]

    def walk(self):
        yield self
        for c in self.children:
            if isinstance(c, Node):
                yield from c.walk()


class Document(HTMLParser):
    VOID = {'area', 'base', 'br', 'col', 'embed', 'hr', 'img', 'input', 'link',
            'meta', 'param', 'source', 'track', 'wbr'}

    def __init__(self, source):
        super().__init__(convert_charrefs=True)
        self.root = Node()
        self.stack = [self.root]
        self.feed(source)
        self.close()

    def handle_starttag(self, tag, attrs):
        node = Node(tag, attrs)
        self.stack[-1].children.append(node)
        if tag == 'br':
            self.stack[-1].children.append('\n')
        if tag not in self.VOID:
            self.stack.append(node)

    def handle_startendtag(self, tag, attrs):
        self.handle_starttag(tag, attrs)
        if tag not in self.VOID:
            self.handle_endtag(tag)

    def handle_endtag(self, tag):
        for i in range(len(self.stack) - 1, 0, -1):
            if self.stack[i].tag == tag:
                del self.stack[i:]
                break

    def handle_data(self, data):
        self.stack[-1].children.append(data)

    def by_id(self, identity):
        matches = [n for n in self.root.walk() if n.attrs.get('id') == identity]
        if len(matches) != 1:
            raise ValueError(f'expected exactly one #{identity}; found {len(matches)}')
        return matches[0]


def normalize(text):
    return ' '.join(text.split())


def tables(section):
    return [[[normalize(cell.text()) for cell in row.children
              if isinstance(cell, Node) and cell.tag in ('th', 'td')]
             for row in table.find('tr')] for table in section.find('table')]


def text_without_tables(node):
    if node.tag in ('table', 'script', 'style'):
        return ''
    return ''.join(text_without_tables(c) if isinstance(c, Node) else c
                   for c in node.children)


def verify_artifact(payload: dict, html: str) -> None:
    ok, reason = gate_payload(payload)
    if not ok:
        raise ValueError(f'payload gate: {reason}')
    actual = Document(html)
    embedded = actual.by_id('pastdue-report')
    if embedded.tag != 'script' or embedded.attrs.get('type') != 'application/json':
        raise ValueError('pastdue-report must be an application/json script')
    if json.loads(embedded.text()) != payload:
        raise ValueError('embedded payload differs from verified snapshot')
    body = actual.root.find('body')
    if len(body) != 1 or body[0].attrs.get('data-mode') != 'ops':
        raise ValueError('default body mode must be ops')
    expected = Document(render_ops_shell(payload))
    expected_sections = expected.root.find('section')
    required_ids = [n.attrs['id'] for n in expected_sections]
    actual_order = [n.attrs.get('id') for n in actual.root.find('section')
                    if n.attrs.get('id') in required_ids]
    if actual_order != required_ids:
        raise ValueError('canonical sections missing, duplicated or reordered')
    for section in expected_sections:
        identity = section.attrs['id']
        target = actual.by_id(identity)
        if tables(target) != tables(section):
            raise ValueError(f'#{identity}: displayed table content/order mismatch')
        # Canonical narrative text may be styled or accompanied by decorative text.
        required = normalize(text_without_tables(section))
        if required not in normalize(text_without_tables(target)):
            raise ValueError(f'#{identity}: narrative/coverage/gaps mismatch')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('payload', type=Path)
    parser.add_argument('artifact', type=Path)
    args = parser.parse_args()
    try:
        verify_artifact(json.loads(args.payload.read_text(encoding='utf-8')),
                        args.artifact.read_text(encoding='utf-8'))
    except (OSError, ValueError, KeyError, TypeError) as exc:
        parser.exit(1, f'VERIFY failed: {exc}\n')
    print('Content verified; browser appearance/functionality NOT_RUN.')


if __name__ == '__main__':
    main()
