package com.claw.service;

/**
 * 高德 POI 结果。
 */
public class PoiResult {
    private String id = "";
    private String name = "";
    private String type = "";
    private String typeCode = "";
    private String address = "";
    private String tel = "";
    private String location = "";
    private String businessArea = "";
    private String tag = "";
    private int distanceMeters = -1;
    private double rating;
    private double cost;
    private int originalRank;
    private boolean detailEnriched;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = normalize(id);
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = normalize(name);
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = normalize(type);
    }

    public String getTypeCode() {
        return typeCode;
    }

    public void setTypeCode(String typeCode) {
        this.typeCode = normalize(typeCode);
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = normalize(address);
    }

    public String getTel() {
        return tel;
    }

    public void setTel(String tel) {
        this.tel = normalize(tel);
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = normalize(location);
    }

    public String getBusinessArea() {
        return businessArea;
    }

    public void setBusinessArea(String businessArea) {
        this.businessArea = normalize(businessArea);
    }

    public String getTag() {
        return tag;
    }

    public void setTag(String tag) {
        this.tag = normalize(tag);
    }

    public int getDistanceMeters() {
        return distanceMeters;
    }

    public void setDistanceMeters(int distanceMeters) {
        this.distanceMeters = distanceMeters;
    }

    public double getRating() {
        return rating;
    }

    public void setRating(double rating) {
        this.rating = rating;
    }

    public double getCost() {
        return cost;
    }

    public void setCost(double cost) {
        this.cost = cost;
    }

    public int getOriginalRank() {
        return originalRank;
    }

    public void setOriginalRank(int originalRank) {
        this.originalRank = originalRank;
    }

    public boolean isDetailEnriched() {
        return detailEnriched;
    }

    public void setDetailEnriched(boolean detailEnriched) {
        this.detailEnriched = detailEnriched;
    }

    public boolean hasCompleteInfo() {
        return !address.isBlank() && !tel.isBlank();
    }

    public boolean hasRating() {
        return rating > 0;
    }

    public boolean isNearBy() {
        return distanceMeters >= 0;
    }

    public void mergeMissingFields(PoiResult other) {
        if (other == null) {
            return;
        }
        if (id.isBlank()) id = other.id;
        if (name.isBlank()) name = other.name;
        if (type.isBlank()) type = other.type;
        if (typeCode.isBlank()) typeCode = other.typeCode;
        if (address.isBlank()) address = other.address;
        if (tel.isBlank()) tel = other.tel;
        if (location.isBlank()) location = other.location;
        if (businessArea.isBlank()) businessArea = other.businessArea;
        if (tag.isBlank()) tag = other.tag;
        if (distanceMeters < 0 && other.distanceMeters >= 0) distanceMeters = other.distanceMeters;
        if (rating <= 0 && other.rating > 0) rating = other.rating;
        if (cost <= 0 && other.cost > 0) cost = other.cost;
        detailEnriched = detailEnriched || other.detailEnriched;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
