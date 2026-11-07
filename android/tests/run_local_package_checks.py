"""Exercise local imports against the same parser used at Android startup."""
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
jdk = Path(os.environ.get("MARCOMOVIE_JDK") or os.environ["JAVA_HOME"]) / "bin"
src = root / "android/src/main/java/com/pilipala/marcomovie"
parser = root / "android/tests/lib/json-20240303.jar"
with tempfile.TemporaryDirectory() as output, tempfile.TemporaryDirectory() as imports:
    subprocess.run([jdk / "javac", "-encoding", "UTF-8", "-cp", parser, "-d", output,
                    root / "shared/java/com/pilipala/source/Warehouse.java", src / "Cms.java", src / "LocalSourcePackage.java", src / "RequestScope.java",
                    root / "android/tests/LocalPackageChecks.java"], check=True)
    subprocess.run([jdk / "java", "-cp", output + os.pathsep + str(parser),
                    "com.pilipala.marcomovie.LocalPackageChecks", imports], check=True)
