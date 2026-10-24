package com.nexusid.config;
public final class AppConfig {
    private AppConfig() {}
    public static final String DB_URL = System.getenv().getOrDefault("NEXUSID_DB_URL", "jdbc:mysql://localhost:3306/nexusid_university?useSSL=false&serverTimezone=Asia/Kolkata");
    public static final String DB_USER = System.getenv().getOrDefault("NEXUSID_DB_USER", "root");
    public static final String DB_PASSWORD = System.getenv().getOrDefault("NEXUSID_DB_PASSWORD", "1234");
    public static final String UNIVERSITY_NAME = "NexusID Technical University";
}
