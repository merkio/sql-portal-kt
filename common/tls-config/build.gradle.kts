// TLS options for every outbound connection, as JSSE objects. No portal code.
plugins {
    id("sqlportal.kotlin-library")
}

dependencies {
    implementation(libs.bouncycastle.pkix)
}
