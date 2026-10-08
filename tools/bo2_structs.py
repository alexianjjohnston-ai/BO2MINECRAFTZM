"""Struct layouts of Black Ops II zone assets, read from OpenAssetTools' T6_Assets.h (GPL-3.0, so it is not in this repo: `python tools/bo2_setup.py --fetch-header` downloads it into the library).
Gives sizeof/offsets as the 32-bit Windows build lays them out, plus a reader that follows 32-bit pointers inside a memory image.
  s = Structs(); s.sizeof('GfxWorld'); s.offsetof('GfxWorld', 'dpvs'); s.read(mem, 'GfxSurface', addr) -> dict"""
import sys, os, re, struct
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from bo2_paths import HEADER

PRIM = {'char': (1, 'b'), 'signed char': (1, 'b'), 'unsigned char': (1, 'B'), 'bool': (1, 'B'), 'byte': (1, 'B'), 'short': (2, 'h'), 'unsigned short': (2, 'H'),
	'uint16_t': (2, 'H'), 'int16_t': (2, 'h'), 'int': (4, 'i'), 'unsigned int': (4, 'I'), 'unsigned': (4, 'I'), 'uint32_t': (4, 'I'), 'int32_t': (4, 'i'), 'float': (4, 'f'),
	'long': (4, 'i'), 'unsigned long': (4, 'I'), 'int64_t': (8, 'q'), 'uint64_t': (8, 'Q'), 'double': (8, 'd'), 'void': (1, 'B'), 'size_t': (4, 'I'), 'uint8_t': (1, 'B'), 'int8_t': (1, 'b')}


class T:  # a resolved type: kind prim|ptr|struct|array|union
	def __init__(s, kind, size, align, **kw): s.kind, s.size, s.align = kind, size, align; s.__dict__.update(kw)


def tokenize(src):
	src = re.sub(r'/\*.*?\*/', ' ', src, flags=re.S)
	src = re.sub(r'//[^\n]*', ' ', src)
	src = '\n'.join(l for l in src.split('\n') if not l.strip().startswith('#'))
	return re.findall(r'[A-Za-z_][A-Za-z_0-9]*|0x[0-9A-Fa-f]+|\d+|<<|>>|[{}();,*\[\]:=+\-/<>|&~]', src)


class Structs:
	def __init__(self, path=HEADER):
		self.toks = tokenize(open(path, encoding='utf-8', errors='replace').read())
		self.i = 0
		self.defs = {}      # name -> ('struct'|'union', members|None, align)
		self.typedefs = {}  # name -> (tokens-resolved type spec, align)
		self.enums = {}     # constant -> int
		self.enum_types = {}  # enum name -> byte size
		self.cache = {}
		self.parse()

	# ---- parsing ----
	def peek(self, k=0): return self.toks[self.i + k] if self.i + k < len(self.toks) else None
	def next(self): t = self.toks[self.i]; self.i += 1; return t
	def expect(self, t):
		got = self.next()
		if got != t: raise SyntaxError('expected %s got %s near %s' % (t, got, ' '.join(self.toks[self.i - 8:self.i + 4])))

	def attr(self):
		"""type_align32(N) / gcc_align32(N) / tdef_align32(N) -> N or None"""
		if self.peek() and re.fullmatch(r'(type|gcc|tdef)_align(32)?', self.peek()):
			self.next(); self.expect('('); n = int(self.next()); self.expect(')'); return n
		return None

	def const_expr(self):
		"""small integer expression until , ; ] }"""
		depth, parts = 0, []
		while True:
			t = self.peek()
			if t == '(': depth += 1
			if t == ')':
				if depth == 0: break
				depth -= 1
			if depth == 0 and t in (',', ';', ']', '}'): break
			parts.append(self.next())
		e = ' '.join(str(self.enums.get(p, p)) for p in parts)
		try: return int(eval(e, {'__builtins__': {}}, {}))
		except Exception: return 0

	def parse(self):
		while self.i < len(self.toks):
			t = self.peek()
			if t in ('namespace',): self.next(); self.next(); self.expect('{'); continue
			if t == '}' or t == ';': self.next(); continue
			if t == 'enum': self.parse_enum(); continue
			if t == 'typedef': self.parse_typedef(); continue
			if t in ('struct', 'union'): self.parse_struct_decl(); continue
			self.next()

	def parse_enum(self):
		self.next()
		ename = None
		if self.peek() not in ('{', ':'): ename = self.next()
		esize = 4
		if self.peek() == ':':
			self.next(); bt = [self.next()]
			while self.peek() in ('char', 'int', 'short', 'long'): bt.append(self.next())
			esize = PRIM.get(' '.join(bt), (4,))[0]
		if ename: self.enum_types[ename] = esize
		if self.peek() != '{':  # forward use, e.g. 'enum X member;' handled by member parser, not here
			return
		self.next(); val = 0
		while self.peek() != '}':
			name = self.next()
			if self.peek() == '=': self.next(); val = self.const_expr()
			self.enums[name] = val; val += 1
			if self.peek() == ',': self.next()
		self.next()
		if self.peek() == ';': self.next()

	def parse_typedef(self):
		self.next()
		align = self.attr()
		spec = self.parse_type_spec()
		decl = self.parse_declarator()
		self.expect(';')
		self.typedefs[decl['name']] = (spec, decl, align)

	def parse_struct_decl(self):
		kind = self.next()
		align = self.attr()
		if self.peek() not in ('{',) and self.peek(1) in ('{', ';', ':'):
			name = self.next()
		else:
			name = None
		if self.peek() == '{':
			members = self.parse_members()
			if name: self.defs[name] = (kind, members, align)
			if self.peek() == ';': self.next()
		else:
			if self.peek() == ';': self.next()

	def parse_members(self):
		self.expect('{'); out = []
		while self.peek() != '}':
			m_align = self.attr()
			if self.peek() in ('struct', 'union') and (self.peek(1) == '{' or (self.peek(2) == '{') or re.fullmatch(r'(type|gcc|tdef)_align(32)?', self.peek(1) or '')):
				# inline definition (maybe anonymous)
				save = self.i
				kind = self.next(); a2 = self.attr()
				nm = self.next() if self.peek() != '{' else None
				if self.peek() == '{':
					members = self.parse_members()
					tag = nm or '$anon%d' % self.i
					self.defs[tag] = (kind, members, a2)
					spec = (kind, tag)
					decls = self.parse_declarators()
					self.expect(';')
					if not decls: decls = [{'name': None, 'ptr': 0, 'dims': [], 'bits': None}]
					for d in decls: out.append((spec, d, m_align))
					continue
				self.i = save
			spec = self.parse_type_spec()
			for d in self.parse_declarators(): out.append((spec, d, m_align))
			self.expect(';')
		self.next()
		return out

	def parse_type_spec(self):
		words = []
		while self.peek() in ('const', 'volatile'): self.next()
		t = self.next()
		if t in ('struct', 'union', 'enum'):
			return (t, self.next())
		words.append(t)
		if t in ('unsigned', 'signed', 'long', 'short', 'char', 'int'):
			while self.peek() in ('long', 'int', 'char', 'short', 'unsigned', 'signed'): words.append(self.next())
			if t in ('unsigned', 'signed') and len(words) == 1 and re.match(r'[A-Za-z_]', self.peek() or '') and self.peek(1) in (';', ',', '[', ':'): pass
		while self.peek() in ('const', 'volatile'): self.next()
		return ('name', ' '.join(words))

	def parse_declarators(self):
		out = []
		while self.peek() != ';':
			out.append(self.parse_declarator())
			if self.peek() == ',': self.next()
		return out

	def parse_declarator(self):
		ptr = 0
		while self.peek() in ('*', 'const', 'volatile'):
			if self.next() == '*': ptr += 1
		if self.peek() == '(':  # function/array pointer: (*name)[3] or (*name)(...)
			self.next(); self.expect('*'); name = self.next(); self.expect(')')
			dims = []
			while self.peek() == '[': self.next(); self.const_expr(); self.expect(']')
			if self.peek() == '(':
				d = 0
				while True:
					t = self.next()
					if t == '(': d += 1
					if t == ')':
						d -= 1
						if d == 0: break
			return {'name': name, 'ptr': 1, 'dims': [], 'bits': None}
		name = self.next() if self.peek() not in (';', ',', ':') else None
		dims = []
		while self.peek() == '[':
			self.next(); dims.append(self.const_expr()); self.expect(']')
		bits = None
		if self.peek() == ':': self.next(); bits = self.const_expr()
		return {'name': name, 'ptr': ptr, 'dims': dims, 'bits': bits}

	# ---- layout ----
	def resolve_spec(self, spec):
		kind, n = spec
		if kind in ('struct', 'union'): return self.layout(n)
		if kind == 'enum': return T('prim', 4, 4, fmt='i')
		if kind == 'name' and n in self.enum_types:
			sz = self.enum_types[n]; return T('prim', sz, sz, fmt={1: 'B', 2: 'H', 4: 'I'}[sz])
		if n in PRIM:
			sz, f = PRIM[n]; return T('prim', sz, sz, fmt=f)
		if n in self.typedefs:
			sp, d, al = self.typedefs[n]
			t = self.apply(self.resolve_spec(sp), d)
			if al: t = T(t.kind, t.size, max(t.align, al), **{k: v for k, v in t.__dict__.items() if k not in ('kind', 'size', 'align')})
			return t
		if n in self.defs: return self.layout(n)
		raise KeyError('unknown type ' + str(n))

	def apply(self, base, d):
		t = base
		for _ in range(d['ptr']): t = T('ptr', 4, 4, to=t)
		for dim in reversed(d['dims']): t = T('array', t.size * dim, t.align, of=t, n=dim)
		return t

	def layout(self, name):
		if name in self.cache: return self.cache[name]
		kind, members, align = self.defs[name]
		t = T(kind, 0, 1, name=name, fields=[], by={})
		self.cache[name] = t
		off = 0; maxal = 1; unit = None  # bitfield state: (start offset, size, used bits)
		for spec, d, m_align in members:
			try: base = self.resolve_spec(spec)
			except KeyError as e:
				self.cache.pop(name, None); raise KeyError('%s.%s: %s' % (name, d['name'], e))
			if d['bits'] is not None:
				u = base.size
				if kind == 'union': continue
				if unit and unit[1] == u and unit[2] + d['bits'] <= u * 8:
					unit = (unit[0], u, unit[2] + d['bits'])
				else:
					off = (off + u - 1) // u * u
					unit = (off, u, d['bits']); off += u
				maxal = max(maxal, u)
				continue
			unit = None
			mt = self.apply(base, d)
			al = max(mt.align, m_align or 1)
			if kind == 'union':
				mo = 0; off = max(off, mt.size)
			else:
				off = (off + al - 1) // al * al; mo = off; off += mt.size
			maxal = max(maxal, al)
			if d['name'] is None:  # anonymous member: hoist
				if mt.kind in ('struct', 'union'):
					for f in mt.fields: t.fields.append((f[0], f[1] + mo, f[2])); t.by[f[0]] = (f[1] + mo, f[2])
				continue
			t.fields.append((d['name'], mo, mt)); t.by[d['name']] = (mo, mt)
		maxal = max(maxal, align or 1)
		t.align = maxal; t.size = (off + maxal - 1) // maxal * maxal
		return t

	# ---- queries / reading ----
	def type(self, name): return self.layout(name)
	def sizeof(self, name): return self.layout(name).size
	def offsetof(self, name, *path):
		o, t = 0, self.layout(name)
		for p in path:
			fo, t = t.by[p]; o += fo
		return o

	def read(self, mem, name, addr, depth=0):
		"""Decode one struct from mem.read(addr, n); pointers stay as ints, arrays become lists, nested structs dicts."""
		t = self.layout(name) if isinstance(name, str) else name
		raw = mem(addr, t.size)
		return self._decode(t, raw, 0)

	def _decode(self, t, raw, off):
		if t.kind == 'prim': return struct.unpack_from('<' + t.fmt, raw, off)[0]
		if t.kind == 'ptr': return struct.unpack_from('<I', raw, off)[0]
		if t.kind == 'array':
			e = t.of
			if e.kind == 'prim' and e.size == 1: return bytes(raw[off:off + t.n])
			return [self._decode(e, raw, off + i * e.size) for i in range(t.n)]
		return {n: self._decode(ft, raw, off + fo) for n, fo, ft in t.fields}
