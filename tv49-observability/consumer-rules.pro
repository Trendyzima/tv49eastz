# Keep the SDK integration provider and public media boundary during R8 shrinking.
-keep class com.tv49east.observability.TV49ObservabilityProvider { public *; }
-keep class com.tv49east.integrations.** { public *; }
