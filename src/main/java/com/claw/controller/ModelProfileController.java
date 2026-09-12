package com.claw.controller;
import com.claw.dto.*; import com.claw.service.*; import jakarta.servlet.http.HttpServletRequest; import jakarta.validation.Valid; import org.springframework.web.bind.annotation.*; import java.util.List;
@RestController @RequestMapping("/api/settings/models")
public class ModelProfileController {
 private final ModelProfileService service; public ModelProfileController(ModelProfileService service){this.service=service;}
 @GetMapping public List<ModelProfileResponse> list(HttpServletRequest r){return service.list(user(r).userId());}
 @PostMapping public ModelProfileResponse create(@Valid @RequestBody ModelProfileRequest q,HttpServletRequest r){return service.save(user(r).userId(),null,q);}
 @PutMapping("/{id}") public ModelProfileResponse update(@PathVariable Long id,@Valid @RequestBody ModelProfileRequest q,HttpServletRequest r){return service.save(user(r).userId(),id,q);}
 @DeleteMapping("/{id}") public void delete(@PathVariable Long id,HttpServletRequest r){service.delete(user(r).userId(),id);}
 @PostMapping("/{id}/test") public java.util.Map<String,Object> test(@PathVariable Long id,HttpServletRequest r){return service.test(user(r).userId(),id);}
 private AuthenticatedUser user(HttpServletRequest r){return (AuthenticatedUser)r.getAttribute(AuthController.USER_ATTRIBUTE);}
}
