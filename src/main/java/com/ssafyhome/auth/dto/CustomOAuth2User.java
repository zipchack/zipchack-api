package com.ssafyhome.auth.dto;

import com.ssafyhome.user.entity.UserEntity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.*;

public class CustomOAuth2User implements OAuth2User {

	private final UserEntity user;

	public CustomOAuth2User(UserEntity user) {

		this.user = user;
	}

	@Override
	public Map<String, Object> getAttributes() {
		return null;
	}

	@Override
	public Collection<? extends GrantedAuthority> getAuthorities() {

		Collection<GrantedAuthority> authorities = new ArrayList<>();
		authorities.add((GrantedAuthority) () -> "ROLE_USER");

		return authorities;
	}

	@Override
	public String getName() {
		return user.getUserId();
	}

	public String getEmail() {return user.getUserEmail(); }

	public long getSeq() {
		return user.getUserSeq();
	}
}
