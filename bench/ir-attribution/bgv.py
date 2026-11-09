"""A reader of Graal's binary graph dumps (.bgv, the format jdk.graal.compiler.graphio.GraphProtocol writes,
versions 6-8): groups, graphs, nodes with their class and properties (nodeSourcePosition included), edges and blocks
skipped. Only what IR attribution needs (attribute.py).

Usage as a module: for group_path, title, nodes in read(path): ...  where nodes is a list of Node.
"""
import struct

BEGIN_GROUP, BEGIN_GRAPH, CLOSE_GROUP, BEGIN_DOCUMENT = 0, 1, 2, 3
POOL_NEW, POOL_STRING, POOL_ENUM, POOL_CLASS, POOL_METHOD, POOL_NULL, POOL_NODE_CLASS, POOL_FIELD, \
    POOL_SIGNATURE, POOL_NODE_SOURCE_POSITION, POOL_NODE = range(11)
PROPERTY_POOL, PROPERTY_INT, PROPERTY_LONG, PROPERTY_DOUBLE, PROPERTY_FLOAT, PROPERTY_TRUE, PROPERTY_FALSE, \
    PROPERTY_ARRAY, PROPERTY_SUBGRAPH = range(9)


class Method:
    __slots__ = ('cls', 'name')

    def __init__(self, cls, name):
        self.cls, self.name = cls, name

    def __str__(self):
        return f'{self.cls}.{self.name}'


class Position:
    """A node's source position: the method and bci, and the caller's position (the inlining chain)."""
    __slots__ = ('method', 'bci', 'caller')

    def __init__(self, method, bci, caller):
        self.method, self.bci, self.caller = method, bci, caller

    def chain(self):
        p = self
        while p is not None:
            yield p
            p = p.caller


class NodeClass:
    __slots__ = ('name', 'inputs', 'successors')

    def __init__(self, name, inputs, successors):
        self.name, self.inputs, self.successors = name, inputs, successors


class Node:
    __slots__ = ('id', 'cls', 'props')

    def __init__(self, id, cls, props):
        self.id, self.cls, self.props = id, cls, props


class Reader:
    def __init__(self, data):
        self.d, self.i, self.pool = data, 0, {}
        if data[:4] != b'BIGV':
            raise ValueError('not a BGV file')
        self.major = data[4]
        self.i = 6

    def byte(self):
        b = self.d[self.i]
        self.i += 1
        return b

    def short(self):
        v, = struct.unpack_from('>H', self.d, self.i)
        self.i += 2
        return v

    def int(self):
        v, = struct.unpack_from('>i', self.d, self.i)
        self.i += 4
        return v

    def skip(self, n):
        self.i += n

    def string(self):
        n = self.int()
        if n < 0:
            return None
        s = self.d[self.i:self.i + n].decode('utf-8', 'replace')
        self.i += n
        return s

    def bytes_(self):
        n = self.int()
        if n > 0:
            self.i += n

    def entry(self, kind):
        if kind == POOL_STRING:
            return self.string()
        if kind == POOL_ENUM:
            self.pool_obj()
            return ('enum', self.int())
        if kind == POOL_CLASS:
            name = self.string()
            if self.byte() == 1:
                for _ in range(self.int()):
                    self.pool_obj()
            return name
        if kind == POOL_METHOD:
            cls = self.pool_obj()
            name = self.pool_obj()
            self.pool_obj()
            self.int()
            self.bytes_()
            return Method(cls, name)
        if kind == POOL_NODE_CLASS:
            cls = self.pool_obj()
            self.string()
            inputs = []
            for _ in range(self.short()):
                inputs.append(self.byte() == 0)
                self.pool_obj()
                self.pool_obj()
            successors = []
            for _ in range(self.short()):
                successors.append(self.byte() == 0)
                self.pool_obj()
            return NodeClass(cls, inputs, successors)
        if kind == POOL_FIELD:
            self.pool_obj()
            self.pool_obj()
            self.pool_obj()
            self.int()
            return 'field'
        if kind == POOL_SIGNATURE:
            for _ in range(self.short()):
                self.pool_obj()
            self.pool_obj()
            return 'signature'
        if kind == POOL_NODE_SOURCE_POSITION:
            method = self.pool_obj()
            bci = self.int()
            if self.major >= 6:
                while self.pool_obj() is not None:
                    self.string()
                    self.int()
                    self.int()
                    self.int()
            else:
                if self.pool_obj() is not None:
                    self.int()
            caller = self.pool_obj()
            return Position(method, bci, caller)
        if kind == POOL_NODE:
            self.int()
            self.pool_obj()
            return 'node'
        raise ValueError(f'pool kind {kind} at {self.i}')

    def pool_obj(self):
        t = self.byte()
        if t == POOL_NULL:
            return None
        if t == POOL_NEW:
            index = self.short()
            kind = self.byte()
            obj = self.entry(kind)
            self.pool[index] = obj
            return obj
        return self.pool[self.short()]

    def prop_value(self):
        t = self.byte()
        if t == PROPERTY_POOL:
            return self.pool_obj()
        if t == PROPERTY_INT:
            return self.int()
        if t in (PROPERTY_LONG, PROPERTY_DOUBLE):
            self.skip(8)
            return None
        if t == PROPERTY_FLOAT:
            self.skip(4)
            return None
        if t == PROPERTY_TRUE:
            return True
        if t == PROPERTY_FALSE:
            return False
        if t == PROPERTY_ARRAY:
            sub = self.byte()
            n = self.int()
            if sub == PROPERTY_DOUBLE:
                self.skip(8 * n)
            elif sub == PROPERTY_INT:
                self.skip(4 * n)
            else:
                for _ in range(n):
                    self.pool_obj()
            return None
        if t == PROPERTY_SUBGRAPH:
            self.graph_body()
            return None
        raise ValueError(f'property type {t} at {self.i}')

    def properties(self):
        n = self.short()
        if n == 0xFFFF:
            n = self.int()
        props = {}
        for _ in range(n):
            key = self.pool_obj()
            props[key] = self.prop_value()
        return props

    def graph_body(self):
        self.properties()
        nodes = []
        for _ in range(self.int()):
            nid = self.int()
            cls = self.pool_obj()
            self.byte()
            props = self.properties()
            for direct in cls.inputs:
                if direct:
                    self.skip(4)
                else:
                    self.skip(4 * self.short())
            for direct in cls.successors:
                if direct:
                    self.skip(4)
                else:
                    self.skip(4 * self.short())
            nodes.append(Node(nid, cls, props))
        for _ in range(self.int()):
            self.int()
            self.skip(4 * self.int())
            self.skip(4 * self.int())
        return nodes


def read(path):
    """Yields (group names, graph title, nodes) for every graph in the file."""
    with open(path, 'rb') as f:
        r = Reader(f.read())
    groups = []
    while r.i < len(r.d):
        t = r.byte()
        if t == BEGIN_GROUP:
            name = r.pool_obj()
            r.pool_obj()
            r.pool_obj()
            r.int()
            r.properties()
            groups.append(str(name))
        elif t == CLOSE_GROUP:
            groups.pop()
        elif t == BEGIN_DOCUMENT:
            r.properties()
        elif t == BEGIN_GRAPH:
            r.int()
            fmt = r.string()
            args = [r.prop_value() for _ in range(r.int())]
            try:
                title = fmt % tuple(args) if args else fmt
            except (TypeError, ValueError):
                title = fmt
            try:
                body = r.graph_body()
            except struct.error:
                # the dump's last graph, cut off when the JVM exited while writing it
                return
            yield list(groups), title, body
        else:
            raise ValueError(f'record type {t} at {r.i - 1}')
