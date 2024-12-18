package com.ssafyhome.spot.service;

import com.ssafyhome.common.api.kakao.KakaoClient;
import com.ssafyhome.common.api.sgis.SGISClient;
import com.ssafyhome.common.api.sgis.SGISUtil;
import com.ssafyhome.common.mapper.GeometryMapper;
import com.ssafyhome.spot.dao.SpotMapper;
import com.ssafyhome.common.api.kakao.dto.KakaoPlaceDto;
import com.ssafyhome.common.api.sgis.dto.SgisGeoCode;
import com.ssafyhome.spot.entity.CategoryEntity;
import com.ssafyhome.common.entity.GeometryEntity;
import com.ssafyhome.spot.entity.SpotEntity;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;


@Slf4j
@Service
public class SpotInternalService {

    private final String COMPLETE_KEY_FORMAT = "SPOT_API_TASK:%s:COMPLETE";
    private final String LOCK_KEY_FORMAT = "SPOT_API_TASK:%s:LOCK";

    private final RedisTemplate<String, String> redisTemplate;
    private final RedissonClient redissonClient;
    private final KakaoClient kakaoClient;
    private final SGISClient sgisClient;
    private final SGISUtil sgisUtil;
    private final SpotMapper spotMapper;
    private final GeometryMapper geometryMapper;
    private final ExecutorService executorService;

    public SpotInternalService(
            RedisTemplate<String, String> redisTemplate,
            RedissonClient redissonClient,
            KakaoClient kakaoClient,
            SGISClient sgisClient,
            SGISUtil sgisUtil,
            SpotMapper spotMapper,
            GeometryMapper geometryMapper,
            ExecutorService executorService
    ) {

        this.redisTemplate = redisTemplate;
        this.redissonClient = redissonClient;
        this.kakaoClient = kakaoClient;
        this.sgisClient = sgisClient;
        this.sgisUtil = sgisUtil;
        this.spotMapper = spotMapper;
        this.geometryMapper = geometryMapper;
        this.executorService = executorService;
    }

    @Transactional
    public void getSpotsFromAPI(String dongCode) throws InterruptedException {

        String TASK_COMPLETE_KEY = String.format(COMPLETE_KEY_FORMAT, dongCode);
        String TASK_LOCK_KEY = String.format(LOCK_KEY_FORMAT, dongCode);

        if (isCompleted(TASK_COMPLETE_KEY)) {
            log.info(dongCode + " is already updated");
            return;
        }

        RLock lock = redissonClient.getLock(TASK_LOCK_KEY);
        if (lock.tryLock(10, 30, TimeUnit.SECONDS)) {
            log.info(dongCode + "lock acquired");
            if (isCompleted(TASK_COMPLETE_KEY)) {
                log.info(dongCode + " is already updated");
            }
            else {
                log.info(dongCode + " save task starting");
                List<SpotEntity> spotEntityList = new ArrayList<>();
                Map<String, List<KakaoPlaceDto.Document>> kakaoPlaceDtoMap = connectWithKakaoAPI(dongCode);

                int size = kakaoPlaceDtoMap.values().stream().mapToInt(List::size).sum();
                ConcurrentHashMap<String, Boolean> processingSpotMap = new ConcurrentHashMap<>();
                CountDownLatch countDownLatch = new CountDownLatch(size);

                for (String key : kakaoPlaceDtoMap.keySet()) {
                    for(KakaoPlaceDto.Document document : kakaoPlaceDtoMap.get(key)) {
                        executorService.submit(() -> {
                            try {
                                Boolean existingValue = processingSpotMap.putIfAbsent(document.getId(), true);
                                if (existingValue != null) return;

                                spotEntityList.add(convertToSpotEntity(document, key));
                            } catch (Exception e) {
                                log.error(e.getMessage());
                            } finally {
                                countDownLatch.countDown();
                            }
                        });
                    }
                }

                try {
                    countDownLatch.await();
                } catch (InterruptedException e) {
                    log.error(e.getMessage());
                }

                spotMapper.insertSpots(spotEntityList);
                redisTemplate.opsForValue().set(
                        TASK_COMPLETE_KEY,
                        String.valueOf(LocalDateTime.now()),
                        1, TimeUnit.HOURS
                );
            }
            lock.unlock();
        }
    }

    private boolean isCompleted(String TASK_COMPLETE_KEY) {

        Boolean isCompleted = redisTemplate.hasKey(TASK_COMPLETE_KEY);
        return Boolean.TRUE.equals(isCompleted);
    }

    private Map<String, List<KakaoPlaceDto.Document>> connectWithKakaoAPI(String dongCode) {

        Map<String, List<KakaoPlaceDto.Document>> kakaoPlaceDtoMap = new HashMap<>();
        GeometryEntity geometry = geometryMapper.selectByDongCode(dongCode);
        List<CategoryEntity> categoryEntities = spotMapper.getCategories();
        CountDownLatch countDownLatch = new CountDownLatch(categoryEntities.size());

        for (CategoryEntity category : categoryEntities) {
            executorService.execute(() -> {
                try{
                    List<KakaoPlaceDto.Document> kakaoPlaceDtoList = new ArrayList<>();
                    int pages = 1;
                    int maxItems = 45;
                    if (category.isCategoryType()) {
                        do {
                            KakaoPlaceDto kakaoPlaceDto = kakaoClient.searchCategoryPlace(
                                category.getCategoryCode(),
                                geometry.getCenterLng(),
                                geometry.getCenterLat(),
                                (int)geometry.getRadius() + 1,
                                15,
                                pages
                            );
                            kakaoPlaceDtoList.addAll(kakaoPlaceDto.getDocuments());
                            log.info(kakaoPlaceDto.toString());
                            maxItems = kakaoPlaceDto.getMeta().getPageableCount();
                        } while (++pages * 15 <= maxItems);
                    }
                    else {
                        do {
                            KakaoPlaceDto kakaoPlaceDto = kakaoClient.searchKeywordPlace(
                                category.getCategoryCode(),
                                geometry.getCenterLng(),
                                geometry.getCenterLat(),
                                (int)geometry.getRadius() + 1,
                                15,
                                pages
                            );
                            log.info(kakaoPlaceDto.toString());
                            maxItems = kakaoPlaceDto.getMeta().getPageableCount();
                        } while (++pages * 15 <= maxItems);
                    }
                    kakaoPlaceDtoMap.put(category.getCategoryName(), kakaoPlaceDtoList);
                } finally {
                    countDownLatch.countDown();
                }
            });
        }

        try {
            countDownLatch.await();
        } catch (InterruptedException e) {
            log.error(e.getMessage());
        }

        return kakaoPlaceDtoMap;
    }

    private SpotEntity convertToSpotEntity(KakaoPlaceDto.Document document, String category) {

        SpotEntity spotEntity = SpotEntity.builder()
                .spotSeq(document.getId())
                .spotName(document.getPlaceName())
                .spotType(category)
                .jibun(document.getAddressName())
                .roadNm(document.getRoadAddressName())
                .latitude(document.getY())
                .longitude(document.getX())
                .build();

        SgisGeoCode sgisGeoCode = sgisClient.getGeocode(sgisUtil.getAccessToken(), spotEntity.getJibun());
        SgisGeoCode.Result result = sgisGeoCode.getResult();
        SgisGeoCode.Result.ResultData resultData = result.getResultdata().get(0);
        spotEntity.setSggCd(resultData.getLegCd().substring(0,5));
        spotEntity.setUmdCd(resultData.getLegCd().substring(5,10));
        spotEntity.setUmdNm(resultData.getLegNm());
        return spotEntity;
    }

}
