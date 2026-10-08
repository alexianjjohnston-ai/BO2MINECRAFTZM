"""Freeze OpenAssetTools' Unlinker (a 32-bit program) right after it has loaded a Black Ops II zone, then read the zone's structures out of its memory.
The Unlinker cannot write BSP / collision / effects to disk, but it has already decoded them; in a 32-bit process the zone structs sit exactly as T6_Assets.h lays them out.
Read-only on the game files (the Unlinker only reads them); the process is killed afterwards.
  with Capture(zone_ff, oat, bo2) as cap:  cap.read(addr, n), cap.find(b'bytes') -> [addr], cap.find_ptrs(addr) -> [addr of dwords == addr]"""
import ctypes, ctypes.wintypes as W, os, struct, subprocess, threading, time

k32 = ctypes.WinDLL('kernel32', use_last_error=True)
nt = ctypes.WinDLL('ntdll')
k32.OpenProcess.restype = W.HANDLE
k32.ReadProcessMemory.argtypes = [W.HANDLE, W.LPCVOID, W.LPVOID, ctypes.c_size_t, ctypes.POINTER(ctypes.c_size_t)]
PROCESS_ALL = 0x1F0FFF
MEM_COMMIT, PAGE_NOACCESS, PAGE_GUARD = 0x1000, 0x01, 0x100


class MBI(ctypes.Structure):
	_fields_ = [('BaseAddress', ctypes.c_void_p), ('AllocationBase', ctypes.c_void_p), ('AllocationProtect', W.DWORD), ('RegionSize', ctypes.c_size_t),
		('State', W.DWORD), ('Protect', W.DWORD), ('Type', W.DWORD)]


class FT(ctypes.Structure): _fields_ = [('lo', W.DWORD), ('hi', W.DWORD)]


class Capture:
	"""Runs `Unlinker --list <zone>` with its output pipe left unread: once the zone is loaded the listing fills the pipe, the process blocks on write
	and sits there with the whole zone in memory. When its CPU time stops moving it is suspended and read."""
	def __init__(self, zone_ff, oat, bo2, search_extra=None, assets=None):
		self.args = [oat, '--no-color', '--list']
		if search_extra: self.args += ['--search-path', search_extra]
		self.args.append(zone_ff)
		self.cwd = os.path.dirname(oat)
		self.lines = []
		self.tiny = False

	def cpu(self):
		c, e, k, u = FT(), FT(), FT(), FT()
		k32.GetProcessTimes(self.h, ctypes.byref(c), ctypes.byref(e), ctypes.byref(k), ctypes.byref(u))
		return (k.hi << 32 | k.lo) + (u.hi << 32 | u.lo)

	def __enter__(self):
		try: return self._start(self.args)
		except RuntimeError:  # a tiny zone lists in less than the pipe holds: ask for much more output so the process still blocks
			try: self.p.kill()
			except Exception: pass
			self.tiny = True
			return self._start(self.args)

	def _start(self, args):
		if self.tiny:  # a pipe with a 1 byte buffer: the very first line the process prints blocks it
			import _winapi, msvcrt
			r, w = _winapi.CreatePipe(None, 1)
			fd = msvcrt.open_osfhandle(int(w), 0)
			self.p = subprocess.Popen(args, stdout=fd, stderr=fd, cwd=self.cwd)
			os.close(fd); self._r = r
		else:
			self.p = subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, cwd=self.cwd)
		self.h = k32.OpenProcess(PROCESS_ALL, False, self.p.pid)
		last, still, t0 = -1, 0, time.time()
		while time.time() - t0 < 300:
			time.sleep(0.12)
			if self.p.poll() is not None: raise RuntimeError('Unlinker exited before it could be frozen (listing too short for the pipe?)')
			t = self.cpu()
			still = still + 1 if t == last else 0
			last = t
			if still >= 3 and time.time() - t0 > 0.5: break
		else: raise RuntimeError('Unlinker never settled')
		nt.NtSuspendProcess(self.h)
		self.regions = None
		return self

	def __exit__(self, *a):
		try: self.p.kill()
		except Exception: pass
		k32.CloseHandle(self.h)

	def read(self, addr, n):
		buf = ctypes.create_string_buffer(n); got = ctypes.c_size_t(0)
		if not k32.ReadProcessMemory(self.h, ctypes.c_void_p(addr), buf, n, ctypes.byref(got)) or got.value != n:
			raise MemoryError('read %x+%d failed' % (addr, n))
		return buf.raw

	def try_read(self, addr, n):
		try: return self.read(addr, n)
		except MemoryError: return None

	def iter_regions(self):
		if self.regions is None:
			self.regions = []; a = 0x10000; mbi = MBI()
			while a < 0xFFFF0000 and k32.VirtualQueryEx(self.h, ctypes.c_void_p(a), ctypes.byref(mbi), ctypes.sizeof(mbi)):
				if mbi.State == MEM_COMMIT and not (mbi.Protect & (PAGE_NOACCESS | PAGE_GUARD)) and mbi.Protect != 0:
					self.regions.append((mbi.BaseAddress or 0, mbi.RegionSize))
				a = (mbi.BaseAddress or 0) + mbi.RegionSize
		return self.regions

	def scan(self, pattern, align=1):
		"""All addresses where `pattern` occurs (aligned to `align`)."""
		out = []
		for base, size in self.iter_regions():
			off = 0
			while off < size:
				n = min(size - off, 16 << 20)
				chunk = self.try_read(base + off, n)
				if chunk:
					i = chunk.find(pattern)
					while i >= 0:
						if (base + off + i) % align == 0: out.append(base + off + i)
						i = chunk.find(pattern, i + 1)
				off += max(1, n - len(pattern) + 1) if n == 16 << 20 else n
		return out

	def find_ptrs(self, addr): return self.scan(struct.pack('<I', addr), 4)

	def cstr(self, addr, limit=256):
		if not addr: return None
		d = self.try_read(addr, limit)
		if d is None:
			d = b''
			for i in range(limit):
				c = self.try_read(addr + i, 1)
				if not c or c == b'\0': break
				d += c
			return d.decode('latin1')
		return d.split(b'\0', 1)[0].decode('latin1')
