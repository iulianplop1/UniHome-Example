import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

// Invoked by Maven's exec plugin with resolved runtime dependencies.
// The JVM argument file avoids command-line length limits during service startup.
class ExampleClasspath {
    public static void main(String[] args) throws Exception {
        String cp = System.getProperty("java.class.path").replace("\\", "/").replace("\"", "\\\"");
        Files.writeString(Path.of(args[0]), "-cp\n\"" + cp + "\"\n" + args[1] + "\n", StandardCharsets.UTF_8);
    }
}
