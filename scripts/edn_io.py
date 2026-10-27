"""Small dependency-free EDN reader/writer for development artifacts, not Agent runtime.

Supports namespaced maps in the actual Atlas 2.0 deployment contract. It does
not evaluate tags or Clojure forms. Keywords are represented by leading ':'.
"""
import json
import re
from pathlib import Path


class Keyword(str):
    pass


class EdnSet(list):
    pass


class EdnList(list):
    pass


def loads(text):
    tokens = re.findall(r'"(?:\\.|[^"\\])*"|;[^\n]*|#:[\w.\-]+(?=\s*\{)|#\{|[{}\[\]()]|[^\s,{}\[\]();]+', text)
    tokens = [t for t in tokens if not t.startswith(';')]
    i = 0

    def value():
        nonlocal i
        if i >= len(tokens):
            raise ValueError('Unexpected end of EDN')
        t = tokens[i]
        i += 1
        if t.startswith('#:'):
            ns = t[2:]
            m = value()
            if not isinstance(m, dict):
                raise ValueError('Namespaced map requires a map')
            return {Keyword(':' + ns + '/' + k[1:]) if isinstance(k, Keyword) and '/' not in k else k: v for k, v in m.items()}
        if t == '{':
            m = {}
            while i < len(tokens) and tokens[i] != '}':
                k = value()
                if isinstance(k, list):
                    k = tuple(k)
                if i >= len(tokens) or tokens[i] == '}':
                    raise ValueError('Odd EDN map forms')
                v = value()
                if k in m:
                    raise ValueError('Duplicate EDN key: ' + str(k))
                m[k] = v
            if i >= len(tokens):
                raise ValueError('Unclosed EDN map')
            i += 1
            return m
        if t in ('[', '(', '#{'):
            end = {'[': ']', '(': ')', '#{': '}'}[t]
            items = []
            while i < len(tokens) and tokens[i] != end:
                items.append(value())
            if i >= len(tokens):
                raise ValueError('Unclosed EDN sequence')
            i += 1
            return EdnSet(items) if t == '#{' else EdnList(items) if t == '(' else items
        if t in ('}', ']', ')'):
            raise ValueError('Unexpected closing delimiter')
        if t.startswith('"'):
            return json.loads(t)
        if t == 'nil':
            return None
        if t in ('true', 'false'):
            return t == 'true'
        if t.startswith(':'):
            return Keyword(t)
        if re.fullmatch(r'[+-]?\d+', t):
            return int(t)
        if re.fullmatch(r'[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?', t):
            return float(t)
        raise ValueError('Unsupported EDN token: ' + t)

    result = value()
    if i != len(tokens):
        raise ValueError('Trailing EDN forms')
    return result


def dumps(v, indent=0):
    if v is None:
        return 'nil'
    if isinstance(v, bool):
        return 'true' if v else 'false'
    if isinstance(v, Keyword):
        return str(v)
    if isinstance(v, str):
        return json.dumps(v, ensure_ascii=False)
    if isinstance(v, dict):
        return '{' + ('\n' + ' ' * (indent + 2)).join(
            dumps(k) + ' ' + dumps(x, indent + 2) for k, x in v.items()) + '}'
    if isinstance(v, (list, tuple)):
        a, b = ('#{', '}') if isinstance(v, EdnSet) else ('(', ')') if isinstance(v, EdnList) else ('[', ']')
        return a + ' '.join(dumps(x, indent + 1) for x in v) + b
    return repr(v)


def read(path):
    return loads(Path(path).read_text(encoding='utf-8'))


def write(path, value):
    Path(path).write_text(dumps(value) + '\n', encoding='utf-8')


K = Keyword
