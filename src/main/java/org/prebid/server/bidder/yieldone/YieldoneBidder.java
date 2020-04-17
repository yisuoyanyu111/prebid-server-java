package org.prebid.server.bidder.yieldone;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iab.openrtb.request.Banner;
import com.iab.openrtb.request.BidRequest;
import com.iab.openrtb.request.Format;
import com.iab.openrtb.request.Imp;
import com.iab.openrtb.response.BidResponse;
import com.iab.openrtb.response.SeatBid;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.core.http.HttpMethod;
import org.prebid.server.bidder.Bidder;
import org.prebid.server.bidder.model.BidderBid;
import org.prebid.server.bidder.model.BidderError;
import org.prebid.server.bidder.model.HttpCall;
import org.prebid.server.bidder.model.HttpRequest;
import org.prebid.server.bidder.model.Result;
import org.prebid.server.exception.PreBidException;
import org.prebid.server.json.DecodeException;
import org.prebid.server.json.JacksonMapper;
import org.prebid.server.proto.openrtb.ext.ExtPrebid;
import org.prebid.server.proto.openrtb.ext.request.yieldone.ExtImpYieldone;
import org.prebid.server.proto.openrtb.ext.response.BidType;
import org.prebid.server.util.HttpUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Yieldone {@link Bidder} implementation.
 */
public class YieldoneBidder implements Bidder<BidRequest> {
    private static final TypeReference<ExtPrebid<?, ExtImpYieldone>> YIELDONE_EXT_TYPE_REFERENCE =
            new TypeReference<ExtPrebid<?, ExtImpYieldone>>() {
            };
    private static final String DEFAULT_BID_CURRENCY = "USD";
    private final String endpointUrl;
    private final JacksonMapper mapper;

    public YieldoneBidder(String endpointUrl, JacksonMapper mapper) {
        this.endpointUrl = HttpUtil.validateUrl(Objects.requireNonNull(endpointUrl));
        this.mapper = Objects.requireNonNull(mapper);
    }

    @Override
    public Result<List<HttpRequest<BidRequest>>> makeHttpRequests(BidRequest request) {
        final List<BidderError> errors = new ArrayList<>();
        final List<Imp> validImps = new ArrayList<>();

        for (Imp imp : request.getImp()) {
            try {
                final Imp validImp = validateImp(imp);
                final ExtImpYieldone extImp = parseImpExt(imp);

                validImps.add(validImp);

            } catch (PreBidException e) {
                errors.add(BidderError.badInput(e.getMessage()));
            }
        }

        final BidRequest outgoingRequest = request.toBuilder().imp(validImps).build();
        final String body = mapper.encode(outgoingRequest);

        return Result.of(Collections.singletonList(
                HttpRequest.<BidRequest>builder()
                        .method(HttpMethod.POST)
                        .uri(endpointUrl)
                        .headers(HttpUtil.headers())
                        .payload(outgoingRequest)
                        .body(body)
                        .build()),
                errors);
    }

    private Imp validateImp(Imp imp) {
        final Banner banner = imp.getBanner();
        if (banner != null) {
            if (banner.getH() == null && banner.getW() == null && banner.getFormat().size() > 0) {
                final Format firstFormat = banner.getFormat().get(0);
                final Banner modifiedBanner = banner.toBuilder()
                        .h(firstFormat.getH())
                        .w(firstFormat.getW())
                        .build();
                return imp.toBuilder().banner(modifiedBanner).build();
            }
        }
        return imp;
    }

    private ExtImpYieldone parseImpExt(Imp imp) {
        try {
            return mapper.mapper().convertValue(imp.getExt(), YIELDONE_EXT_TYPE_REFERENCE).getBidder();
        } catch (IllegalArgumentException e) {
            throw new PreBidException(e.getMessage(), e);
        }
    }

    @Override
    public Result<List<BidderBid>> makeBids(HttpCall<BidRequest> httpCall, BidRequest bidRequest) {
        final int statusCode = httpCall.getResponse().getStatusCode();
        if (statusCode == HttpResponseStatus.NO_CONTENT.code()) {
            return Result.of(Collections.emptyList(), Collections.emptyList());
        } else if (statusCode == HttpResponseStatus.BAD_REQUEST.code()) {
            return Result.emptyWithError(BidderError.badInput("bad request"));
        } else if (statusCode != HttpResponseStatus.OK.code()) {
            return Result.emptyWithError(BidderError.badServerResponse(String.format("Unexpected HTTP status %s.",
                    statusCode)));
        }

        try {
            final BidResponse bidResponse = decodeBodyToBidResponse(httpCall);
            final List<BidderBid> bidderBids = bidResponse.getSeatbid().stream()
                    .filter(Objects::nonNull)
                    .map(SeatBid::getBid)
                    .filter(Objects::nonNull)
                    .flatMap(Collection::stream)
                    .map(bid -> BidderBid.of(bid, getBidType(bid.getImpid(), bidRequest.getImp()),
                            DEFAULT_BID_CURRENCY))
                    .collect(Collectors.toList());
            return Result.of(bidderBids, Collections.emptyList());
        } catch (PreBidException e) {
            return Result.emptyWithError(BidderError.badInput(e.getMessage()));
        }
    }

    private BidResponse decodeBodyToBidResponse(HttpCall<BidRequest> httpCall) {
        try {
            return mapper.decodeValue(httpCall.getResponse().getBody(), BidResponse.class);
        } catch (DecodeException e) {
            throw new PreBidException(e.getMessage(), e);
        }
    }

    private static BidType getBidType(String impId, List<Imp> imps) {
        for (Imp imp : imps) {
            if (imp.getId().equals(impId)) {
                if (imp.getBanner() != null) {
                    return BidType.banner;
                } else if (imp.getVideo() != null) {
                    return BidType.video;
                }
            }
        }
        throw new PreBidException(String.format("Failed to find impression %s", impId));
    }

    @Override
    public Map<String, String> extractTargeting(ObjectNode ext) {
        return Collections.emptyMap();
    }
}
