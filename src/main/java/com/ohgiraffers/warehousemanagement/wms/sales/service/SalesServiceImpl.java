package com.ohgiraffers.warehousemanagement.wms.sales.service;

import com.ohgiraffers.warehousemanagement.wms.common.exception.InventoryNotFoundException;
import com.ohgiraffers.warehousemanagement.wms.common.exception.ProductNotFoundException;
import com.ohgiraffers.warehousemanagement.wms.common.exception.StoreNotFoundException;
import com.ohgiraffers.warehousemanagement.wms.inventory.model.DTO.InventoryViewDTO;
import com.ohgiraffers.warehousemanagement.wms.inventory.service.InventoryService;
import com.ohgiraffers.warehousemanagement.wms.product.model.entity.Product;
import com.ohgiraffers.warehousemanagement.wms.product.model.repository.ProductRepository;
import com.ohgiraffers.warehousemanagement.wms.product.service.ProductService;
import com.ohgiraffers.warehousemanagement.wms.sales.model.dto.SalesDTO;
import com.ohgiraffers.warehousemanagement.wms.sales.model.entity.Sales;
import com.ohgiraffers.warehousemanagement.wms.sales.model.entity.SalesItem;
import com.ohgiraffers.warehousemanagement.wms.sales.model.entity.SalesStatus;
import com.ohgiraffers.warehousemanagement.wms.sales.repository.SalesItemsRepository;
import com.ohgiraffers.warehousemanagement.wms.sales.repository.SalesRepository;
import com.ohgiraffers.warehousemanagement.wms.store.model.dto.StoreDTO;
import com.ohgiraffers.warehousemanagement.wms.store.model.entity.Store;
import com.ohgiraffers.warehousemanagement.wms.store.repository.StoreRepository;
import com.ohgiraffers.warehousemanagement.wms.store.service.StoreService;
import com.ohgiraffers.warehousemanagement.wms.user.model.dto.LogUserDTO;
import com.ohgiraffers.warehousemanagement.wms.user.model.dto.UserDTO;
import com.ohgiraffers.warehousemanagement.wms.user.service.UserService;
import jakarta.transaction.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class SalesServiceImpl implements SalesService {
    private final SalesRepository salesRepository;
    private final SalesItemsRepository salesItemsRepository;
    private final ProductService productService;
    private final UserService userService;
    private final InventoryService inventoryService;
    private final StoreService storeService;
    private final ProductRepository productRepository;
    private final StoreRepository storeRepository;

    @Autowired
    public SalesServiceImpl(SalesRepository salesRepository, SalesItemsRepository salesItemsRepository, ProductService productService, UserService userService, InventoryService inventoryService, StoreService storeService, ProductRepository productRepository, StoreRepository storeRepository) {
        this.salesRepository = salesRepository;
        this.salesItemsRepository = salesItemsRepository;
        this.productService = productService;
        this.userService = userService;
        this.inventoryService = inventoryService;
        this.storeService = storeService;
        this.productRepository = productRepository;
        this.storeRepository = storeRepository;
    }

    public List<SalesDTO> getAllSales() {
        // 비즈니스로직 아직 추가안함!!
        // 조회해서 등록한거 최신순으로 보여줌
        List<Sales> findAll = salesRepository.findAllByOrderBySalesIdDesc();
        List<SalesDTO> salesLists = new ArrayList<>();

        // 수주 상품 목록 Sales 엔티티에서 꺼냄
        for (Sales salesEntity : findAll) {
            LogUserDTO user = userService.getUserInfoForLogging(salesEntity.getUserId());
            StoreDTO store = storeService.findById(salesEntity.getStoreId());
            SalesDTO salesDTO = new SalesDTO(
                    salesEntity.getSalesId(),
                    salesEntity.getStoreId(),
                    store.getStoreName(),
                    store.getStoreAddress(),
                    salesEntity.getUserId(),
                    user.getUserName(),
                    user.getUserPhone(),
                    salesEntity.getSalesDate(),
                    salesEntity.getShippingDueDate(),
                    salesEntity.getSalesStatus(),
                    salesEntity.getSalesCreatedAt(),
                    salesEntity.getSalesUpdatedAt()
            );

            salesLists.add(salesDTO);
        }
        return salesLists;
    }

    @Transactional
    public int createSales(SalesDTO salesDTO, Long userId) {
        Store store = storeRepository.findById(salesDTO.getStoreId()).orElseThrow(() -> new StoreNotFoundException("존재하지 않는 점포입니다."));
        for (Integer productId : salesDTO.getProductIds()) {
            Product product = productRepository.findById(productId).orElseThrow(() -> new ProductNotFoundException(productId +"번 상품은 존재하지 않는 상품입니다."));
        }

        // 수주 정보 저장
        Sales salesEntity = new Sales.Builder()
                .storeId(salesDTO.getStoreId())
                .userId(userId)
                .salesDate(salesDTO.getSalesDate())
                .shippingDueDate(salesDTO.getSalesDate().plusDays(3))
                .salesStatus(SalesStatus.PENDING)
                .salesCreatedAt(LocalDateTime.now())
                .build();
        Sales savedSales = salesRepository.save(salesEntity);

        // 수주 리스트 저장
        List<SalesItem> salesItemList = new ArrayList<>();
        for (int i = 0; i < salesDTO.getProductIds().size(); i++) {
            Integer productId = salesDTO.getProductIds().get(i);
            Integer quantity = salesDTO.getQuantity().get(i);

            String lotNumber;
            try {
                lotNumber = inventoryService.findTopByProductIdOrderByInventoryExpiryDateAsc(productId).getLotNumber();
            } catch (IllegalArgumentException e) {
                throw new InventoryNotFoundException(productId + "번 상품에 재고가 없습니다. 수주 등록을 다시 시도해주세요.");
            }

            SalesItem salesItem = new SalesItem.Builder()
                    .salesId(savedSales)
                    .productId(productId)
                    .salesItemsQuantity(quantity)
                    .lotNumber(lotNumber)
                    .build();
            salesItemList.add(salesItem);
            salesItemsRepository.save(salesItem);
        }

        // 저장 후 상세페이지 보여주기 위해 ID만 반환
        int salesId = savedSales.getSalesId();

        return salesId;
    }

    public SalesDTO getSalesById(Integer salesId) {
        // 익셉션 전역 핸들러 짜야됨
        Sales findSales = salesRepository.findById(salesId).orElseThrow(
                () -> new NullPointerException("수주 데이터 없음"));

        List<Integer> productIds = new ArrayList<>();
        List<String> productNames = new ArrayList<>();
        List<Integer> pricePerBoxList = new ArrayList<>();
        List<Integer> quantityList = new ArrayList<>();
        List<Integer> totalPriceList = new ArrayList<>();
        System.out.println("findSalesById : "+findSales);

        for (SalesItem item : findSales.getSalesItems()) {
            System.out.println("반복문호출 " + item);
            Product product = productService.findProductById(item.getProductId());
            System.out.println("item리스트 : "+item);

            productIds.add(product.getProductId());
            productNames.add(product.getProductName());
            pricePerBoxList.add(product.getPricePerBox());
            quantityList.add(item.getSalesItemsQuantity());
            totalPriceList.add(product.getPricePerBox() * item.getSalesItemsQuantity());
        }

        List<Integer> quantity = findSales.getSalesItems().stream().map(SalesItem::getSalesItemsQuantity).collect(Collectors.toList());
        LogUserDTO user = userService.getUserInfoForLogging(findSales.getUserId());
        StoreDTO store = storeService.findById(findSales.getStoreId());

        SalesDTO findDTO = new SalesDTO(
                findSales.getSalesId(),
                findSales.getStoreId(),
                store.getStoreName(),
                store.getStoreAddress(),
                findSales.getUserId(),
                user.getUserName(),
                user.getUserPhone(),
                findSales.getSalesDate(),
                findSales.getShippingDueDate(),
                findSales.getSalesStatus(),
                findSales.getSalesCreatedAt(),
                findSales.getSalesUpdatedAt() == null ? null : findSales.getSalesUpdatedAt()
        );

        findDTO.setProductIds(productIds);
        findDTO.setProductNames(productNames);
        findDTO.setQuantity(quantity);
        findDTO.setPricePerBox(pricePerBoxList);
        findDTO.setTotalPrice(totalPriceList);

        return findDTO;
    }

    @Transactional
    public SalesDTO updateSales(Integer salesId, SalesDTO salesDTO) {
        Sales findSales = salesRepository.findById(salesId).orElseThrow(
                () -> new NullPointerException("수정할 수주 데이터 없음"));

        // 사용자가 수정한값과 기존값이 다를때만 수정함!
        if (!Objects.equals(salesDTO.getStoreId(), findSales.getStoreId())) {
            findSales.setStoreId(salesDTO.getStoreId());
        }
        
        if (!Objects.equals(salesDTO.getUserId(), findSales.getUserId())) {
            findSales.setUserId(salesDTO.getUserId());
        }
        
        if (!Objects.equals(salesDTO.getSalesDate(), findSales.getSalesDate())) {
            findSales.setSalesDate(salesDTO.getSalesDate());
        }
        
        if (!Objects.equals(salesDTO.getShippingDueDate(), findSales.getShippingDueDate())) {
            findSales.setShippingDueDate(salesDTO.getShippingDueDate());
        }

        // 수정이 일어났으니 무조건 업데이트시킴
        findSales.setSalesUpdatedAt(LocalDateTime.now());
        Sales savedEntity = salesRepository.save(findSales);

        // 이 sales Id를 가진 수주 물품 목록 삭제하고
        salesItemsRepository.deleteBySalesId(findSales);

        // 새로등록할거. 어차피 수주 등록상태에서만 수정 가능하기 때문에 출고로 아예 넘어가지 않은 상태라 수정하지 않은 상품들도 로트넘버를 새로 갱신해줘도 상관없음
        List<SalesItem> newItems = new ArrayList<>();
        for (int i = 0; i < salesDTO.getProductIds().size(); i++) {
            String lotNumber = inventoryService.findTopByProductIdOrderByInventoryExpiryDateAsc(salesDTO.getProductIds().get(i)).getLotNumber();
            SalesItem item = new SalesItem.Builder()
                    .salesId(savedEntity)
                    .productId(salesDTO.getProductIds().get(i))
                    .salesItemsQuantity(salesDTO.getQuantity().get(i))
                    .lotNumber(lotNumber)
                    .build();
            newItems.add(item);
        }
        salesItemsRepository.saveAll(newItems);

        salesDTO.setSalesUpdatedAt(savedEntity.getSalesUpdatedAt());
        return salesDTO;
    }

    @Transactional
    public boolean updateStatusSales(Integer salesId, SalesStatus status) {
        Sales findSales = salesRepository.findById(salesId).orElseThrow(
                () -> new NullPointerException("상태를 변경할 수주 데이터 없음"));

        // 등록상태에서만 승인이나 취소 가능
        if (findSales.getSalesStatus() != SalesStatus.PENDING) {
            return false; // InvalidTransactionException 처리필요
        }

        // 승인이랑 취소 상태로만 변경 가능
        if (status != SalesStatus.APPROVED && status != SalesStatus.CANCELED) {
            return false; // InvalidTransactionException 처리필요
        }

        findSales.setSalesStatus(status);
        findSales.setSalesUpdatedAt(LocalDateTime.now());
        return true;
    }

    public List<StoreDTO> searchStoresByName(String storeName) {
        List<StoreDTO> searchResults = storeService.searchByNameContainingAndIsDeletedFalse(storeName);
        return searchResults;
    }

    /*public List<InventoryViewDTO> searchProductsByName(String productName) {
        List<InventoryViewDTO> searchResults = inventoryService.searchByNameContainingAndIsDeletedTrue(productName);
        return searchResults;
    }

    public List<UserDTO> searchUsersByName(String userName) {
        List<UserDTO> searchResults = userService.searchByNameContainingAndIsDeletedTrue(userName);
        return searchResults;
    }*/

    @Override
    public Sales getSalesBySalesId(Integer salesId) {
        return salesRepository.findById(salesId).get();
    }

    @Override
    public SalesItem getSalesItemBySalesId(Integer salesId) {
        return salesItemsRepository.findById(salesId).get();
    }
}
