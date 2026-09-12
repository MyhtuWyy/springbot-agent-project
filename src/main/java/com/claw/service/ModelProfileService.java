package com.claw.service;

import com.claw.dto.*;
import com.claw.entity.UserModelProfileEntity;
import com.claw.repository.UserModelProfileRepository;
import com.claw.util.ConfigUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

@Service
public class ModelProfileService {
    private final UserModelProfileRepository repository; private final SecretValueCodec codec;
    public ModelProfileService(UserModelProfileRepository repository, SecretValueCodec codec){this.repository=repository;this.codec=codec;}
    public List<ModelProfileResponse> list(Long userId){return repository.findByUserIdOrderByUpdatedAtDesc(userId).stream().map(this::response).toList();}
    @Transactional public ModelProfileResponse save(Long userId, Long id, ModelProfileRequest request){
        UserModelProfileEntity p=id==null?new UserModelProfileEntity():repository.findByIdAndUserId(id,userId).orElseThrow();
        p.setUserId(userId); p.setProvider(request.provider().trim()); p.setBaseUrl(normalizeBaseUrl(request.baseUrl())); p.setModel(request.model().trim());
        if(request.apiKey()!=null&&!request.apiKey().isBlank()&&!request.apiKey().startsWith("******")) p.setEncryptedApiKey(codec.encrypt(request.apiKey().trim()));
        if(p.getEncryptedApiKey()==null||p.getEncryptedApiKey().isBlank()) throw new IllegalArgumentException("apiKey is required");
        p.setTemperature(request.temperature()); p.setMaxTokens(request.maxTokens());
        boolean makeDefault=Boolean.TRUE.equals(request.defaultProfile())||repository.countByUserId(userId)==0; p.setDefaultProfile(makeDefault);
        if(makeDefault){repository.findByUserIdOrderByUpdatedAtDesc(userId).forEach(other->{if(!other.equals(p)){other.setDefaultProfile(false);repository.save(other);}});}
        return response(repository.save(p));
    }
    @Transactional public void delete(Long userId,Long id){repository.findByIdAndUserId(id,userId).ifPresent(repository::delete);}
    public Map<String,Object> test(Long userId,Long id){
        UserModelProfileEntity p=repository.findByIdAndUserId(id,userId).orElseThrow();
        String url=p.getBaseUrl().endsWith("/models")?p.getBaseUrl():p.getBaseUrl()+"/models";
        Request request=new Request.Builder().url(url).header("Authorization","Bearer "+codec.decrypt(p.getEncryptedApiKey())).get().build();
        try(Response response=new OkHttpClient.Builder().callTimeout(java.time.Duration.ofSeconds(15)).build().newCall(request).execute()){
            return Map.of("success",response.isSuccessful(),"status",response.code(),"message",response.isSuccessful()?"connection succeeded":"provider rejected the request");
        }catch(Exception e){return Map.of("success",false,"status",0,"message",e.getMessage()==null?"connection failed":e.getMessage());}
    }
    public ModelRuntimeConfig resolve(UserSessionContext context){
        if(context!=null&&context.userId()!=null){var p=repository.findFirstByUserIdAndDefaultProfileTrue(context.userId());if(p.isPresent()) return new ModelRuntimeConfig(p.get().getBaseUrl(),p.get().getModel(),codec.decrypt(p.get().getEncryptedApiKey()),p.get().getTemperature(),p.get().getMaxTokens());}
        return new ModelRuntimeConfig(ConfigUtil.getDashscopeBaseUrl(),ConfigUtil.getBailianModel(),ConfigUtil.getBailianKey(),0.7,2000);
    }
    public ModelProfileResponse response(UserModelProfileEntity p){String key=codec.decrypt(p.getEncryptedApiKey());String masked=key.length()<=8?"******":"******"+key.substring(key.length()-4);return new ModelProfileResponse(p.getId(),p.getProvider(),p.getBaseUrl(),p.getModel(),masked,p.getTemperature(),p.getMaxTokens(),p.isDefaultProfile());}
    private String normalizeBaseUrl(String value){String v=value.trim();return v.endsWith("/")?v.substring(0,v.length()-1):v;}
}
