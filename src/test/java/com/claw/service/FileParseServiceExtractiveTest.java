package com.claw.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileParseServiceExtractiveTest {

    private final FileParseService fileParseService = new FileParseService();

    @Test
    void shouldExtractWholeFirstWeekSection() {
        String content = """
                软件学院 2025-2026-3 学期课程表
                第一周线下讲座；第二周通过腾讯会议开展线上学习；地点：平原湖校区1号报告厅
                周一 7月13日
                讲座名称：厚植国际育人底蕴，赋能学子逐梦远航——软件学院国际化建设成果与海外升学政策解读
                9:00-11:30 李帅洪 主讲人信息：李帅洪
                讲座名称：《数字经济浪潮：手握AI武器，筑牢青年发展竞争力》
                15:30-18:00 梁恒
                周二 7月14日
                讲座名称：以赛促学 筑梦未来——软件学院学科竞赛全路径成长指南
                9:00-11:30 朱泰隆
                讲座名称：计算机软件类专业创新创业专题培训
                15:30-18:00 陈酉宜
                第二周通过腾讯会议开展线上学习
                """;

        String answer = fileParseService.answerQuestionForContent("课程表.pdf", content, "第一周有哪些课程");

        assertTrue(answer.contains("第一周线下讲座，地点：平原湖校区1号报告厅。"));
        assertTrue(answer.contains("1. 7月13日（周一）"));
        assertTrue(answer.contains("时间：9:00-11:30"));
        assertTrue(answer.contains("讲座名称：厚植国际育人底蕴，赋能学子逐梦远航——软件学院国际化建设成果与海外升学政策解读"));
        assertTrue(answer.contains("主讲人：李帅洪"));
        assertTrue(answer.contains("地点：平原湖校区1号报告厅"));
        assertTrue(answer.contains("2. 7月13日（周一）"));
        assertTrue(answer.contains("时间：15:30-18:00"));
        assertTrue(answer.contains("主讲人：梁恒"));
        assertFalse(answer.contains("第二周通过腾讯会议开展线上学习\n时间："));
        assertFalse(answer.contains("【文件问答】"));
        assertFalse(answer.contains("原文依据："));
    }

    @Test
    void shouldReturnOnlyOriginalDateLineWithoutInference() {
        String content = """
                7月13日 上午李帅洪
                7月14日 上午朱泰隆
                """;

        String answer = fileParseService.answerQuestionForContent("课程表.pdf", content, "7月13日");

        assertEquals("7月13日 上午李帅洪", answer);
        assertFalse(answer.contains("7月14日 上午朱泰隆"));
        assertFalse(answer.contains("周六"));
        assertFalse(answer.contains("周日"));
    }

    @Test
    void shouldAvoidDuplicateItemsForWrappedSchedulePdfText() {
        String content = """
                软件学院 2025-2026-3 学期课程表
                第一周线下讲座；第二周通过腾讯会议开展线上学习；地点：平原湖校区1号报告厅
                周一
                7月13日
                讲座名称：厚植国际育人底蕴，
                赋能学子逐梦远航——软件学院
                国际化建设成果与海外升学政策解读
                9:00-11:30 李帅洪
                主讲人信息：李帅洪
                主要内容：软件学院国际化建设成果介绍，海外升学政策解读
                企业专家讲座：《数字经济浪潮：手
                握 AI 武器，筑牢青年发展竞争力》
                15:30-18:00 梁恒
                地点与提示：一号报告厅；7月13日下午15:30开始，请提前15分钟入场
                周二
                7月14日
                讲座名称：以赛促学 筑梦未来——
                软件学院学科竞赛全路径成长指南
                9:00-11:30 朱泰隆
                围绕软件学院学科竞赛体系、参赛路径、备赛方法开展
                讲座名称：计算机软件类专业创新
                创业专题培训
                15:30-18:00 陈酉宜
                周三
                7月15日
                讲座名称：“代码铸机甲，赛场
                启征程”大学生机器人竞赛入门讲座
                9:00-11:30 赵振蒙
                讲座名称：《AI 时代的网络安全
                新认知》
                15:30-18:00 史彦武
                学习形式：线上学习
                """;

        String answer = fileParseService.answerQuestionForContent("课程表.pdf", content, "第一周的内容有什么");

        assertTrue(answer.contains("1. 7月13日（周一）"));
        assertTrue(answer.contains("2. 7月13日（周一）"));
        assertTrue(answer.contains("3. 7月14日（周二）"));
        assertTrue(answer.contains("4. 7月14日（周二）"));
        assertTrue(answer.contains("5. 7月15日（周三）"));
        assertTrue(answer.contains("6. 7月15日（周三）"));
        assertTrue(answer.contains("讲座名称：厚植国际育人底蕴，赋能学子逐梦远航——软件学院国际化建设成果与海外升学政策解读"));
        assertTrue(answer.contains("讲座名称：《数字经济浪潮：手握 AI 武器，筑牢青年发展竞争力》"));
        assertTrue(answer.contains("讲座名称：以赛促学 筑梦未来——软件学院学科竞赛全路径成长指南"));
        assertTrue(answer.contains("讲座名称：计算机软件类专业创新创业专题培训"));
        assertTrue(answer.contains("讲座名称：“代码铸机甲，赛场启征程”大学生机器人竞赛入门讲座"));
        assertTrue(answer.contains("讲座名称：《AI 时代的网络安全新认知》"));
        assertFalse(answer.contains("第1项安排"));
        assertFalse(answer.contains("原文未明确展示"));
        assertFalse(answer.contains("主要内容"));
        assertFalse(answer.contains("学习形式：线上学习"));
    }
}
