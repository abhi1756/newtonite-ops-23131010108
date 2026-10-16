package com.newtonite.ops.identity;

import com.newtonite.ops.common.ApiException;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** Lets controllers declare a {@link CurrentUser} parameter, resolved from the validated JWT. */
@Component
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    private final UserDirectory users;

    public CurrentUserArgumentResolver(UserDirectory users) {
        this.users = users;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType().equals(CurrentUser.class);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Login required");
        }
        CurrentUser user = users.loadCurrentUser(Long.parseLong(jwt.getSubject()));
        if (user == null) {
            // Token is valid but the user no longer exists.
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "User no longer exists");
        }
        return user;
    }
}
