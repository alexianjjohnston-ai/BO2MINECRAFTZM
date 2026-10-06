import com.zombiecraft.bo2.Bo2Assets;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Dev tool: runs the BO2 asset conversion without starting Minecraft. usage: java Convert <gameDir> <bo2Dir> [existing dump folders...] */
public class Convert {
	public static void main(String[] a) throws Exception {
		List<Path> dumps = new ArrayList<>();
		for (int i = 2; i < a.length; i++) dumps.add(Path.of(a[i]));
		long t = System.currentTimeMillis();
		int n = Bo2Assets.prepare(Path.of(a[0]), Path.of(a[1]), dumps, System.out::println);
		System.out.println(n + " models in " + (System.currentTimeMillis() - t) + " ms");
	}
}
