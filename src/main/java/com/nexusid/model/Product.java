package com.nexusid.model;
import java.math.BigDecimal;
public record Product(long id, String name, BigDecimal price, int stock) { @Override public String toString(){ return name + " — ₹" + price + " (stock " + stock + ")"; } }
