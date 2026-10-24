package com.nexusid.model;
public record Outlet(long id, String name, String type) { @Override public String toString(){ return name + " • " + type; } }
