package com.claw.service;

import com.claw.entity.JobPostingEntity;
import com.claw.repository.JobPostingRepository;
import com.claw.util.HttpUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class JobMatchService {
    private static final Logger log = LoggerFactory.getLogger(JobMatchService.class);
    private static final Pattern HTML_TITLE_PATTERN = Pattern.compile("(?is)<title>(.*?)</title>");

    private final JobPostingRepository jobPostingRepository;
    private final JobMatchingService jobMatchingService;

    public JobMatchService(JobPostingRepository jobPostingRepository,
                           JobMatchingService jobMatchingService) {
        this.jobPostingRepository = jobPostingRepository;
        this.jobMatchingService = jobMatchingService;
    }

    @Transactional
    public JobPostingEntity matchUrl(Long userId,
                                     String platform,
                                     String jobTitle,
                                     String companyName,
                                     String city,
                                     String jobUrl,
                                     String jobDescription,
                                     Long resumeDocumentId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId is required");
        }

        String targetUrl = sanitizeUrl(jobUrl);
        if (targetUrl == null) {
            throw new IllegalArgumentException("jobUrl is required");
        }

        String resolvedDescription = resolveJobDescription(jobDescription, targetUrl);
        JobPostingEntity entity = new JobPostingEntity();
        entity.setUserId(userId);
        entity.setPlatform(resolvePlatform(platform, targetUrl));
        entity.setJobUrl(targetUrl);
        entity.setEntryUrl(targetUrl);
        entity.setJobDescription(resolvedDescription);
        entity.setJobTitle(resolveJobTitle(jobTitle, resolvedDescription, targetUrl));
        entity.setCompanyName(blankToNull(companyName));
        entity.setCity(blankToNull(city));
        entity.setResumeDocumentId(resumeDocumentId);
        entity.setStatus("matched");

        JobMatchingService.MatchResult matchResult = jobMatchingService.evaluate(userId, entity);
        entity.setMatchScore(matchResult.score());
        entity.setMatchSummary(matchResult.summary());
        entity.setRagContext(matchResult.ragContext());
        return jobPostingRepository.save(entity);
    }

    @Transactional(readOnly = true)
    public List<JobPostingEntity> list(Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId is required");
        }
        return jobPostingRepository.findByUserIdOrderByUpdatedAtDesc(userId);
    }

    private String resolveJobDescription(String jobDescription, String jobUrl) {
        String description = blankToNull(jobDescription);
        if (description != null) {
            return description;
        }

        String htmlTitle = fetchHtmlTitle(jobUrl);
        if (htmlTitle != null) {
            return htmlTitle + "\n" + jobUrl;
        }
        return jobUrl;
    }

    private String fetchHtmlTitle(String jobUrl) {
        if (jobUrl == null || jobUrl.isBlank()) {
            return null;
        }
        try {
            String body = HttpUtil.doGet(jobUrl);
            Matcher matcher = HTML_TITLE_PATTERN.matcher(body == null ? "" : body);
            if (!matcher.find()) {
                return null;
            }
            String title = matcher.group(1);
            if (title == null) {
                return null;
            }
            title = title.replaceAll("\\s+", " ").trim();
            return title.isBlank() ? null : title;
        } catch (Exception e) {
            log.debug("fetch job html title failed, jobUrl={}", jobUrl, e);
            return null;
        }
    }

    private String resolveJobTitle(String jobTitle, String jobDescription, String jobUrl) {
        String explicit = blankToNull(jobTitle);
        if (explicit != null) {
            return explicit;
        }
        if (jobDescription != null) {
            String firstLine = jobDescription.lines().findFirst().map(String::trim).orElse("");
            if (!firstLine.isBlank() && firstLine.length() <= 120) {
                return firstLine;
            }
        }
        return "岗位匹配";
    }

    private String resolvePlatform(String platform, String jobUrl) {
        String explicit = blankToNull(platform);
        if (explicit != null) {
            return explicit.trim().toLowerCase(Locale.ROOT);
        }
        try {
            String host = URI.create(jobUrl).getHost();
            if (host == null || host.isBlank()) {
                return "unknown";
            }
            if (host.contains("boss")) return "boss";
            if (host.contains("liepin")) return "liepin";
            if (host.contains("lagou")) return "lagou";
            if (host.contains("zhaopin")) return "zhilian";
            if (host.contains("51job")) return "51job";
            return host.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return "unknown";
        }
    }

    private String sanitizeUrl(String text) {
        String value = blankToNull(text);
        if (value == null) {
            return null;
        }
        int start = value.indexOf("http://");
        if (start < 0) {
            start = value.indexOf("https://");
        }
        if (start < 0) {
            return value;
        }
        String candidate = value.substring(start);
        int end = candidate.length();
        while (end > 0 && !isUrlChar(candidate.charAt(end - 1))) {
            end--;
        }
        return end <= 0 ? null : candidate.substring(0, end);
    }

    private boolean isUrlChar(char ch) {
        return Character.isLetterOrDigit(ch)
                || "-._~:/?#[]@!$&'()*+,;=%".indexOf(ch) >= 0;
    }

    private String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }
}
