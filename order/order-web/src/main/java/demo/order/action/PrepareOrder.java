package demo.order.action;

import demo.domain.Action;
import demo.order.domain.Order;
import demo.order.domain.OrderService;
import demo.order.domain.OrderStatus;
import demo.order.event.OrderEvent;
import demo.order.event.OrderEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import org.springframework.web.client.HttpClientErrorException;

@Service
@Transactional
public class PrepareOrder extends Action<Order> {
    private final Logger log = LoggerFactory.getLogger(this.getClass());
    private final OrderService orderService;

    public PrepareOrder(OrderService orderService) {
        this.orderService = orderService;
    }

    public Order apply(Order order) {
        checkOrderState(order);
        order.setStatus(OrderStatus.ORDER_PREPARING);
        order = orderService.update(order);

        try {
            order.appendEvent(new OrderEvent(OrderEventType.ORDER_PREPARING, order));
        } catch (Exception ex) {
            log.error("Could not prepare order", ex);
            order.setStatus(OrderStatus.ORDER_ASSIGNED);
            order = orderService.update(order);
        }

        return order;
    }

    private void checkOrderState(Order order) {
        try {
            Assert.isTrue(order.getStatus() == OrderStatus.ORDER_ASSIGNED,
                    String.format("Order must be in a ORDER_ASSIGNED state. {state=%s}", order.getStatus()));
        } catch (Exception ex) {
            throw new HttpClientErrorException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }
}
