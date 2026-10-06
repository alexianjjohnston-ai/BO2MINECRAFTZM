import com.zombiecraft.bo2.XAnim;

import java.nio.file.*;
import java.util.stream.Stream;

/** Dev tool: parses every compiled xanim under the given folders and reports failures (a correct reader consumes each file exactly). */
public class AnimCheck {
	public static void main(String[] a) throws Exception {
		int ok = 0, bad = 0;
		for (String root : a) {
			try (Stream<Path> s = Files.walk(Path.of(root))) {
				for (Path p : (Iterable<Path>) s.filter(Files::isRegularFile).filter(f -> f.getParent().getFileName().toString().equals("xanim"))::iterator) {
					try { XAnim x = XAnim.read(p); ok++; if (p.getFileName().toString().equals("ai_zombie_walk_v1")) System.out.println(p + ": frames " + x.numFrames + " @" + x.frameRate + " bones " + x.boneNames.length + " looped " + x.looped + " notes " + String.join(",", x.notifyNames)); }
					catch (Exception e) { bad++; if (bad < 15) System.out.println("FAIL " + p + ": " + e); }
				}
			}
		}
		System.out.println(ok + " parsed, " + bad + " failed");
	}
}
