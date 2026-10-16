package com.newtonite.ops.identity;

public record UserAccount(long id, String email, String displayName, String passwordHash, boolean admin) {
}
