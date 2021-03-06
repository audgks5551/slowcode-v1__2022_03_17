package itseasy.mark.api.controller;

import io.jsonwebtoken.Claims;
import itseasy.mark.api.dto.UserDto;
import itseasy.mark.api.vo.RequestUser;
import itseasy.mark.api.vo.ResponseDTO;
import itseasy.mark.api.vo.ResponseUser;
import itseasy.mark.config.properties.AppProperties;
import itseasy.mark.oauth.entity.RoleType;
import itseasy.mark.oauth.entity.UserPrincipal;
import itseasy.mark.service.UserService;
import itseasy.mark.token.AuthToken;
import itseasy.mark.token.AuthTokenProvider;
import itseasy.mark.token.UserRefreshToken;
import itseasy.mark.token.UserRefreshTokenRepository;
import itseasy.mark.utils.CookieUtil;
import itseasy.mark.utils.HeaderUtil;
import itseasy.mark.utils.KeycloakUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.representations.AccessTokenResponse;
import org.modelmapper.ModelMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.util.Date;

import static org.springframework.http.HttpStatus.CREATED;

@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final ModelMapper mapper;
    private final UserService userService;
    private final AuthenticationManager authenticationManager;
    private final AppProperties appProperties;
    private final AuthTokenProvider tokenProvider;
    private final UserRefreshTokenRepository userRefreshTokenRepository;
    private final KeycloakUtil keycloakUtil;

    private final static long THREE_DAYS_MSEC = 259200000;
    private final static String REFRESH_TOKEN = "refresh_token";

    @PostMapping("/signup")
    public ResponseEntity<ResponseDTO> signup(@RequestBody RequestUser user) {
        UserDto userDto = mapper.map(user, UserDto.class); // RequestUser -> UserDto
        UserDto savedUserDto = userService.createUser(userDto); // ?????? ?????? ???????????? ??????

        ResponseUser responseUser = mapper.map(savedUserDto, ResponseUser.class); // UserDto -> ResponseUser

        /**
         * keycloak ?????? ??????
         */
        keycloakUtil.registerUser(user.getUsername(), user.getPassword());

        return ResponseEntity.status(CREATED).body(
                ResponseDTO.put(responseUser, null)
        );
    }

    @PostMapping("/login")
    public ResponseEntity<ResponseDTO> login(
            HttpServletRequest request,
            HttpServletResponse response,
            @RequestBody RequestUser user) {
//        Authentication authentication = authenticationManager.authenticate(
//                new UsernamePasswordAuthenticationToken(
//                        user.getUsername(),
//                        user.getPassword()
//                )
//        );

        String username = user.getUsername();
//        SecurityContextHolder.getContext().setAuthentication(authentication);

        /**
         * ????????? ?????? ??????
         */
        Date now = new Date();
//        AuthToken accessToken = tokenProvider.createAuthToken(
//                username,
//                ((UserPrincipal) authentication.getPrincipal()).getRoleType().getCode(),
//                new Date(now.getTime() + appProperties.getAuth().getTokenExpiry())
//        );
        log.info("????????? ?????? ????????? ???????????? ?????? = {}", new Date(now.getTime() + appProperties.getAuth().getTokenExpiry()));

        /**
         * ???????????? ?????? ??????
         */
        long refreshTokenExpiry = appProperties.getAuth().getRefreshTokenExpiry();
        AuthToken refreshToken = tokenProvider.createAuthToken(
                appProperties.getAuth().getTokenSecret(),
                new Date(now.getTime() + refreshTokenExpiry)
        );

        /**
         * userId refresh token ?????? DB ??????
         */
        UserRefreshToken userRefreshToken = userRefreshTokenRepository.findByUsername(username);
        if (userRefreshToken == null) {
            /**
             * ?????? ?????? ?????? ??????
             */
            userRefreshToken = new UserRefreshToken(username, refreshToken.getToken());
            userRefreshTokenRepository.saveAndFlush(userRefreshToken);
        } else {
            /**
             * DB??? refresh ?????? ????????????
             */
            userRefreshToken.setRefreshToken(refreshToken.getToken());
        }

        int cookieMaxAge = (int) refreshTokenExpiry / 60;

        AccessTokenResponse token = keycloakUtil.getToken(user.getUsername(), user.getPassword());

//        CookieUtil.deleteCookie(request, response, REFRESH_TOKEN);
//        CookieUtil.addCookie(response, REFRESH_TOKEN, token.getRefreshToken(), (int) token.getRefreshExpiresIn());

        return ResponseEntity.status(HttpStatus.OK).body(
                ResponseDTO.put(token, null)
        );
    }

    @GetMapping("/refresh")
    public ResponseEntity<ResponseDTO> refreshToken (HttpServletRequest request, HttpServletResponse response) {

        /**
         * access token ??????
         */
        String accessToken = HeaderUtil.getAccessToken(request);
        AuthToken authToken = tokenProvider.convertAuthToken(accessToken);

        Claims claims = authToken.getExpiredTokenClaims();
        if (claims == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    ResponseDTO.put(null, "?????? ???????????? ?????? ????????? ???????????????.")
            );
        }

        String username = claims.getSubject();
        RoleType roleType = RoleType.of(claims.get("role", String.class));

        /**
         * refresh token ??????
         */
        String refreshToken = CookieUtil.getCookie(request, REFRESH_TOKEN)
                .map(Cookie::getValue)
                .orElse((null));
        AuthToken authRefreshToken = tokenProvider.convertAuthToken(refreshToken);

        if (!authRefreshToken.validate()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    ResponseDTO.put(null, "??? ??? ?????? ???????????? ???????????????")
            );
        }

        /**
         * userId refresh token ?????? DB ??????
         */
        UserRefreshToken userRefreshToken = userRefreshTokenRepository.findByUsernameAndRefreshToken(username, refreshToken);
        if (userRefreshToken == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                    ResponseDTO.put(null, "???????????? ????????? ???????????? ????????????")
            );
        }

        Date now = new Date();
        AuthToken newAccessToken = tokenProvider.createAuthToken(
                username,
                roleType.getCode(),
                new Date(now.getTime() + appProperties.getAuth().getTokenExpiry())
        );

        long validTime = authRefreshToken.getTokenClaims().getExpiration().getTime() - now.getTime();

        /**
         * refresh ?????? ????????? 3??? ????????? ?????? ??????, refresh ?????? ??????
         */
        if (validTime <= THREE_DAYS_MSEC) {
            /**
             * refresh ?????? ??????
             */
            long refreshTokenExpiry = appProperties.getAuth().getRefreshTokenExpiry();

            authRefreshToken = tokenProvider.createAuthToken(
                    appProperties.getAuth().getTokenSecret(),
                    new Date(now.getTime() + refreshTokenExpiry)
            );

            /**
             * DB??? refresh ?????? ????????????
             */
            userRefreshToken.setRefreshToken(authRefreshToken.getToken());

            int cookieMaxAge = (int) refreshTokenExpiry / 60;
            CookieUtil.deleteCookie(request, response, REFRESH_TOKEN);
            CookieUtil.addCookie(response, REFRESH_TOKEN, authRefreshToken.getToken(), cookieMaxAge);
        }

        return ResponseEntity.status(HttpStatus.OK).body(
                ResponseDTO.put(newAccessToken.getToken(), null)
        );
    }
}
