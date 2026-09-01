package io.webboy.springtutorial;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.HandlerInterceptor;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.webboy.springtutorial.Lesson.fact;
import static io.webboy.springtutorial.Lesson.lesson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 레슨 5 — Web MVC (면접 Q57 · Q58 · Q60 · Q62 · Q63 · Q64 · Q66 · Q128)
 *
 * <p>서블릿 컨테이너를 띄우지 않는다 — {@code MockMvc} 의 standalone 모드는
 * {@code DispatcherServlet} 의 처리 경로(바인딩·검증·예외 처리·인터셉터)를 실물로 통과시키되
 * 네트워크와 톰캣만 뺀 것이다. 그래서 여기서 확인한 것은 상태 코드와 응답 그 자체다.
 */
@DisplayName("레슨 5. Web MVC — 요청이 컨트롤러에 닿기까지")
class Lesson05_WebMvc {

    @Test
    @DisplayName("5-1. @RestController 는 반환값이 본문, @Controller 는 뷰 이름이다 (Q57)")
    void restControllerWritesTheBody() throws Exception {
        MockMvc rest = MockMvcBuilders.standaloneSetup(new ApiController()).build();
        MockMvc view = MockMvcBuilders.standaloneSetup(new PageController()).build();

        // @RestController — 객체가 JSON 으로 직렬화되어 본문이 된다
        rest.perform(get("/api/users/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.name").value("사용자-7"));

        // @Controller — 반환한 문자열은 본문이 아니라 '뷰 이름'으로 해석된다
        view.perform(get("/page"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("greeting"))     // greeting 이라는 뷰로 포워드
                .andExpect(content().string(""));         // 본문에 greeting 이 찍히지 않는다

        fact("@RestController /api/users/7", "JSON 본문");
        fact("@Controller /page", "뷰 'greeting' 으로 포워드, 본문 없음");

        lesson("@RestController = @Controller + @ResponseBody. 실체는 '반환값을 어디로 보내는가'의 차이다");
    }

    @Test
    @DisplayName("5-2. @RequestParam 은 기본이 필수 — 빠지면 400 이다 (Q58)")
    void requestParamIsRequiredByDefault() throws Exception {
        // 문자열 응답의 인코딩을 UTF-8 로 지정한다. StringHttpMessageConverter 의 프레임워크
        // 기본값은 ISO-8859-1 이라 한글이 ? 로 깨진다 — 평소엔 Boot 자동 설정이 UTF-8 로
        // 바꿔 주고 있었던 것이고, standalone 모드는 그 민낯을 그대로 드러낸다.
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ApiController())
                .setMessageConverters(
                        new org.springframework.http.converter.StringHttpMessageConverter(
                                java.nio.charset.StandardCharsets.UTF_8),
                        new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter())
                .build();

        mvc.perform(get("/api/search").param("keyword", "스프링"))
                .andExpect(status().isOk());

        // 파라미터를 빼면 컨트롤러에 들어가 보지도 못하고 400 이다
        mvc.perform(get("/api/search"))
                .andExpect(status().isBadRequest());

        // defaultValue 를 주면 없어도 통과한다
        mvc.perform(get("/api/search-with-default"))
                .andExpect(status().isOk())
                .andExpect(content().string("검색어: 전체"));

        fact("keyword 없이 /api/search", "400 Bad Request");
        fact("defaultValue 가 있으면", "200, '전체' 로 대체");

        lesson("'없을 수 있는' 파라미터는 required=false 나 defaultValue 로 선언한다 — 코드가 곧 명세다");
    }

    @Test
    @DisplayName("5-3. @Valid 실패는 400 — 어느 필드가 왜 틀렸는지까지 안다 (Q60·Q61)")
    void validationFailuresBecome400() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ApiController())
                .setControllerAdvice(new ApiErrorAdvice())
                .build();

        // 정상 본문
        mvc.perform(post("/api/members").contentType("application/json")
                        .content("{\"name\":\"김검증\",\"age\":30}"))
                .andExpect(status().isCreated());

        // 빈 이름 + 음수 나이 — 검증이 컨트롤러 진입 전에 막는다
        mvc.perform(post("/api/members").contentType("application/json")
                        .content("{\"name\":\"\",\"age\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").value("이름은 비울 수 없습니다"))
                .andExpect(jsonPath("$.errors.age").value("나이는 0 이상이어야 합니다"));

        fact("검증 실패 응답", "400 + {필드: 메시지} 맵");

        lesson("검증은 컨트롤러 코드가 아니라 선언(@NotBlank·@Min)이 한다 — 메서드 본문은 유효한 값만 본다");
        lesson("실패 응답에 '어느 필드가 왜'를 담아 주는 것까지가 API 설계다(Q63)");
    }

    @Test
    @DisplayName("5-4. 예외 처리는 @RestControllerAdvice 한 곳으로 모은다 (Q62·Q63)")
    void adviceCentralizesErrorHandling() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ApiController())
                .setControllerAdvice(new ApiErrorAdvice())
                .build();

        mvc.perform(get("/api/users/404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("사용자 404 없음"));

        fact("도메인 예외 → 응답", "404 + {code, message}");

        lesson("컨트롤러마다 try-catch 를 두지 않는다 — 예외→응답 변환은 어드바이스 한 곳의 책임이다");
        lesson("이때 스택 트레이스·내부 클래스명을 응답에 싣지 않는 것까지가 보안이다");
    }

    @Test
    @DisplayName("5-5. 생성 응답은 201 + Location 이 정석이다 (Q64)")
    void createdResponsesCarryLocation() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ApiController()).build();

        mvc.perform(post("/api/members").contentType("application/json")
                        .content("{\"name\":\"김생성\",\"age\":20}"))
                .andExpect(status().isCreated())                       // 200 이 아니라 201
                .andExpect(header().string("Location", "/api/members/1"));

        fact("생성 성공", "201 Created + Location: /api/members/1");

        lesson("상태 코드는 장식이 아니라 계약이다 — 클라이언트·프록시·모니터링이 전부 이 숫자로 분기한다");
    }

    @Test
    @DisplayName("5-6. 지원하지 않는 메서드는 405 로 구분해 알린다 (Q64·Q65)")
    void wrongMethodIs405Not404() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ApiController()).build();

        // 경로는 존재한다. 메서드가 다를 뿐이다 — 404 와 구분된다
        mvc.perform(post("/api/search").param("keyword", "x"))
                .andExpect(status().isMethodNotAllowed());

        fact("GET 전용 경로에 POST", "405 Method Not Allowed (404 가 아니다)");

        lesson("404 는 '자원이 없다', 405 는 '자원은 있는데 그 동사가 아니다' — 클라이언트 디버깅이 갈린다");
    }

    @Test
    @DisplayName("5-7. 인터셉터는 핸들러 앞뒤를 감싼다 (Q66)")
    void interceptorsWrapTheHandler() throws Exception {
        List<String> order = new ArrayList<>();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new RecordingController(order))
                .addInterceptors(new RecordingInterceptor(order))
                .build();

        mvc.perform(get("/traced")).andExpect(status().isOk());

        fact("실행 순서", order);
        assertThat(order).containsExactly("preHandle", "핸들러", "postHandle", "afterCompletion");

        lesson("Filter 는 서블릿 앞(스프링 밖), Interceptor 는 핸들러 앞뒤(스프링 안), AOP 는 메서드 단위");
        lesson("인증처럼 컨트롤러 정보가 필요하면 Interceptor, 인코딩·CORS 처럼 전역이면 Filter");
    }

    @Test
    @DisplayName("5-8. 타입이 안 맞는 @PathVariable 도 400 이다 (Q58)")
    void typeMismatchIs400() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ApiController()).build();

        mvc.perform(get("/api/users/abc"))    // long 자리에 문자열
                .andExpect(status().isBadRequest());

        fact("/api/users/abc (long 기대)", "400 — 컨트롤러 진입 전에 걸린다");

        lesson("바인딩·검증·타입 변환의 실패는 전부 '컨트롤러 밖'에서 처리된다 — 그 사실이 코드를 얇게 만든다");
    }

    // ── 레슨용 컨트롤러 ─────────────────────────────────────────────

    record UserResponse(long id, String name) {}

    record MemberRequest(@NotBlank(message = "이름은 비울 수 없습니다") String name,
                         @Min(value = 0, message = "나이는 0 이상이어야 합니다") int age) {}

    static class UserNotFound extends RuntimeException {
        final long id;

        UserNotFound(long id) {
            super("사용자 " + id + " 없음");
            this.id = id;
        }
    }

    @RestController
    @RequestMapping("/api")
    static class ApiController {

        @GetMapping("/users/{id}")
        UserResponse user(@PathVariable long id) {
            if (id == 404) {
                throw new UserNotFound(id);
            }
            return new UserResponse(id, "사용자-" + id);
        }

        @GetMapping("/search")
        String search(@RequestParam String keyword) {
            return "검색어: " + keyword;
        }

        @GetMapping("/search-with-default")
        String searchWithDefault(@RequestParam(defaultValue = "전체") String keyword) {
            return "검색어: " + keyword;
        }

        @PostMapping("/members")
        ResponseEntity<Void> create(@Valid @RequestBody MemberRequest request) {
            return ResponseEntity.created(URI.create("/api/members/1")).build();
        }
    }

    @Controller
    static class PageController {
        @GetMapping("/page")
        String page() {
            return "greeting";   // 본문이 아니라 뷰 이름이다
        }
    }

    @RestControllerAdvice
    static class ApiErrorAdvice {

        record ErrorResponse(String code, String message) {}

        @ExceptionHandler(UserNotFound.class)
        ResponseEntity<ErrorResponse> userNotFound(UserNotFound e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse("USER_NOT_FOUND", e.getMessage()));
        }

        @ExceptionHandler(MethodArgumentNotValidException.class)
        ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException e) {
            Map<String, String> errors = new java.util.LinkedHashMap<>();
            e.getBindingResult().getFieldErrors()
                    .forEach(fe -> errors.put(fe.getField(), fe.getDefaultMessage()));
            return ResponseEntity.badRequest().body(Map.of("errors", errors));
        }
    }

    @RestController
    static class RecordingController {
        private final List<String> order;

        RecordingController(List<String> order) {
            this.order = order;
        }

        @GetMapping("/traced")
        String traced() {
            order.add("핸들러");
            return "ok";
        }
    }

    static class RecordingInterceptor implements HandlerInterceptor {
        private final List<String> order;

        RecordingInterceptor(List<String> order) {
            this.order = order;
        }

        @Override
        public boolean preHandle(jakarta.servlet.http.HttpServletRequest request,
                                 jakarta.servlet.http.HttpServletResponse response, Object handler) {
            order.add("preHandle");
            return true;
        }

        @Override
        public void postHandle(jakarta.servlet.http.HttpServletRequest request,
                               jakarta.servlet.http.HttpServletResponse response, Object handler,
                               org.springframework.web.servlet.ModelAndView modelAndView) {
            order.add("postHandle");
        }

        @Override
        public void afterCompletion(jakarta.servlet.http.HttpServletRequest request,
                                    jakarta.servlet.http.HttpServletResponse response, Object handler,
                                    Exception ex) {
            order.add("afterCompletion");
        }
    }
}
