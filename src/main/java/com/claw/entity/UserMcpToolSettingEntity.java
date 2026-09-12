package com.claw.entity;
import jakarta.persistence.*; import java.time.LocalDateTime;
@Entity @Table(name="user_mcp_tool_setting",uniqueConstraints=@UniqueConstraint(name="uk_user_mcp_tool",columnNames={"user_id","server_key","tool_name"}))
public class UserMcpToolSettingEntity {
 @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id; @Column(name="user_id",nullable=false) private Long userId; @Column(name="server_key",nullable=false,length=80) private String serverKey; @Column(name="tool_name",nullable=false,length=256) private String toolName; @Column(name="enabled",nullable=false) private boolean enabled=true; @Column(name="updated_at",nullable=false) private LocalDateTime updatedAt;
 @PrePersist @PreUpdate public void touch(){updatedAt=LocalDateTime.now();} public void setUserId(Long v){userId=v;} public void setServerKey(String v){serverKey=v;} public void setToolName(String v){toolName=v;} public void setEnabled(boolean v){enabled=v;} public boolean isEnabled(){return enabled;} public String getToolName(){return toolName;}
}
