// TLS options for every outbound connection, as JSSE objects. No portal code.
plugins {
    id("sqlportal.kotlin-serialization")
}

dependencies {
    implementation(libs.bouncycastle.pkix)
}
