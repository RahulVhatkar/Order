package demo.driver.action;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import demo.config.PinotJdbcTemplate;
import demo.domain.Action;
import demo.driver.domain.*;
import demo.order.domain.Order;
import demo.order.domain.OrderService;
import demo.order.domain.OrderStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import org.springframework.web.client.HttpClientErrorException;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Connects an {@link Driver} to an Account.
 *
 * @author Kenny Bastani
 */
@Service
@Transactional
public class FetchOrderRequest extends Action<Driver> {
    private final Logger log = LoggerFactory.getLogger(this.getClass());
    private final DriverService driverService;
    private final PinotJdbcTemplate pinotJdbcTemplate;
    private final OrderService orderService;

    public FetchOrderRequest(DriverService driverService, PinotJdbcTemplate pinotJdbcTemplate,
                             OrderService orderService) {
        this.driverService = driverService;
        this.pinotJdbcTemplate = pinotJdbcTemplate;
        this.orderService = orderService;
    }

    public DriverOrderRequest apply(Driver driver) {
        checkDriverState(driver);
        return findOrderRequest(driver);
    }

    /**
     * This method is used to find an available order request from a restaurant that has not yet been claimed by
     * another driver.
     *
     * @return a {@link DriverOrderRequest} with the {@link Order} details
     */
    private DriverOrderRequest findOrderRequest(Driver driver) {
        // Query Pinot for available orders that are ready for pickup and within 10km of the driver's current location
        NearbyPreparedOrder[] nearbyOrdersArr = {};
        List<Map<String, Object>> orders = null;
        ObjectMapper objectMapper = new ObjectMapper();

        DriverOrderRequest result = null;

        try {
            orders = pinotJdbcTemplate.executeQuery(String.format("""
                    SELECT orderId,\s
                    lastModified as preparedAt, (now() - lastModified) as preparedAge,\s
                    ST_DISTANCE(location_st_point, ST_Point(%s, %s, 1)) as distance
                    FROM orders
                    WHERE distance < 15000.0 AND status = 'ORDER_PREPARED'
                    ORDER BY distance ASC
                    LIMIT 10""", driver.getLon(), driver.getLat()));
            nearbyOrdersArr = objectMapper.readValue(objectMapper.writeValueAsString(orders), NearbyPreparedOrder[].class);
        } catch (SQLException | JsonProcessingException ex) {
            throw new RuntimeException("Could not fetch nearby orders from Pinot datasource", ex);
        }

        try {
            List<NearbyPreparedOrder> preparedOrders = Stream.of(nearbyOrdersArr).collect(Collectors.toList());

            // Check the current state of the top order and return the DriverOrderRequest
            NearbyPreparedOrder selectedOrder = preparedOrders.stream()
                    .filter(o -> {
                        Order currentOrder = orderService.get(o.getOrderId());
                        return currentOrder.getStatus() == OrderStatus.ORDER_PREPARED &&
                                Optional.ofNullable(currentOrder.getDriverId())
                                        .map(id -> !id.equals(driver.getIdentity()))
                                        .orElse(true);
                    })
                    .findFirst()
                    .orElse(null);

            if (selectedOrder != null) {
                Order order = orderService.get(selectedOrder.getOrderId());
                order.setDriverId(driver.getIdentity());
                order = orderService.update(order);
                result = new DriverOrderRequest(order);
            }
        } catch (Exception ex) {
            log.error("Error fetching order", ex);
            throw new RuntimeException("Could not fetch order", ex);
        }

        return result;
    }

    private void checkDriverState(Driver driver) {
        try {
            Assert.isTrue(driver.getDriverStatus() == DriverStatus.DRIVER_ACTIVE &&
                            driver.getAvailabilityStatus() == DriverAvailabilityStatus.DRIVER_ONLINE &&
                            driver.getActivityStatus() == DriverActivityStatus.DRIVER_WAITING,
                    String.format("Driver must be in a DRIVER_ACTIVE state. AvailabilityStatus must be in a " +
                                    "DRIVER_ONLINE state. ActivityStatus must be in a DRIVER_WAITING state. " +
                                    "{state=%s, availability=%s, activity=%s}",
                            driver.getDriverStatus(), driver.getAvailabilityStatus(), driver.getActivityStatus()));
        } catch (Exception ex) {
            throw new HttpClientErrorException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }
}
