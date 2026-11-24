#!/usr/bin/env python3
"""Exercise the zipped Maven repository, without Gradle TestKit classpath injection."""
import json
from pathlib import Path
import subprocess
import tempfile
import zipfile

root = Path(__file__).resolve().parents[1]
version = next(line.split("=", 1)[1] for line in (root / "gradle.properties").read_text().splitlines() if line.startswith("vevVersion="))
bundle = root / f"build/distributions/vev-sql-{version}-maven.zip"
with tempfile.TemporaryDirectory(prefix="vev-release-consumer-") as temporary:
    consumer = Path(temporary)
    repository = consumer / "repository"
    with zipfile.ZipFile(bundle) as archive:
        for name in archive.namelist():
            if not (repository / name).resolve().is_relative_to(repository.resolve()):
                raise SystemExit("Unsafe release archive entry")
        archive.extractall(repository)
    repository_url = json.dumps(repository.as_uri())
    (consumer / "settings.gradle.kts").write_text(f"""
pluginManagement {{ repositories {{ maven {{ url = uri({repository_url}) }}; gradlePluginPortal(); mavenCentral() }} }}
rootProject.name = "release-consumer"
""")
    (consumer / "build.gradle.kts").write_text(f"""
plugins {{ kotlin("jvm") version "2.5.0-Beta2"; id("no.beint.vev") version "{version}"; application }}
repositories {{ maven {{ url=uri({repository_url}) }}; mavenCentral() }}
kotlin {{ jvmToolchain(27); compilerOptions {{ jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_27) }} }}
dependencies {{ implementation("org.postgresql:postgresql:42.7.14"); implementation("no.beint.vev:compiler:{version}") }}
tasks.named<no.beint.vev.gradle.GenerateSql>("generateVev") {{ packageName.set("sample") }}
application {{ mainClass.set("sample.ConsumerKt") }}
""")
    migrations = consumer / "src/main/resources/db/migration"
    migrations.mkdir(parents=True)
    (migrations / "V1__customer.sql").write_text("CREATE TABLE customer(id int,tenant_id int NOT NULL,name text NOT NULL,PRIMARY KEY(id,tenant_id));")
    queries = consumer / "src/main/sql"
    queries.mkdir(parents=True)
    (queries / "create.sql").write_text("-- vev.name: create :exec\nINSERT INTO customer(id,tenant_id,name) VALUES(:id,:tenantId,:name);")
    (queries / "find.sql").write_text("-- vev.name: find :optional\nSELECT id,name FROM customer WHERE tenant_id=:tenantId AND id=:id;")
    kotlin = consumer / "src/main/kotlin/sample"
    kotlin.mkdir(parents=True)
    (kotlin / "Consumer.kt").write_text("""
package sample
import no.beint.vev.Db
import no.beint.vev.compiler.Postgres
import org.postgresql.ds.PGSimpleDataSource
import java.nio.file.Path
fun main() {
    Postgres("").use { pg ->
        pg.migrate(Path.of("src/main/resources/db/migration"))
        val db=Db(PGSimpleDataSource().apply { setURL(pg.url()); user=pg.user(); password=pg.password() })
        db.write(7) { Queries.create(it, 1,"synthetic") }
        check(db.read(7) { Queries.find(it, id=1)?.name }=="synthetic")
        check(db.read(8) { Queries.find(it, id=1) }==null)
        check(db.read(7) { JavaConsumer.name(it) }=="synthetic")
        println("Published Kotlin/Java consumer and tenant isolation verified")
    }
}
""")
    java = consumer / "src/main/java/sample"
    java.mkdir(parents=True)
    (java / "JavaConsumer.java").write_text("package sample; import no.beint.vev.TenantReadScope; public class JavaConsumer { public static String name(TenantReadScope tx) { return Queries.find(tx, 1).getName(); } }")
    for invocation in range(2):
        subprocess.run([str(root / "gradlew"), "check", "run", "--console=plain", "--configuration-cache"], cwd=consumer, check=True)
