package org.prebid.server.adapter.rubicon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iab.openrtb.request.App;
import com.iab.openrtb.request.Banner;
import com.iab.openrtb.request.BidRequest;
import com.iab.openrtb.request.Content;
import com.iab.openrtb.request.Device;
import com.iab.openrtb.request.Format;
import com.iab.openrtb.request.Imp;
import com.iab.openrtb.request.Publisher;
import com.iab.openrtb.request.Site;
import com.iab.openrtb.request.User;
import com.iab.openrtb.request.Video;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.json.Json;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.prebid.server.adapter.OpenrtbAdapter;
import org.prebid.server.adapter.model.ExchangeCall;
import org.prebid.server.adapter.model.HttpRequest;
import org.prebid.server.adapter.rubicon.model.RubiconBannerExt;
import org.prebid.server.adapter.rubicon.model.RubiconBannerExtRp;
import org.prebid.server.adapter.rubicon.model.RubiconDeviceExt;
import org.prebid.server.adapter.rubicon.model.RubiconDeviceExtRp;
import org.prebid.server.adapter.rubicon.model.RubiconImpExt;
import org.prebid.server.adapter.rubicon.model.RubiconImpExtRp;
import org.prebid.server.adapter.rubicon.model.RubiconImpExtRpTrack;
import org.prebid.server.adapter.rubicon.model.RubiconParams;
import org.prebid.server.adapter.rubicon.model.RubiconPubExt;
import org.prebid.server.adapter.rubicon.model.RubiconPubExtRp;
import org.prebid.server.adapter.rubicon.model.RubiconSiteExt;
import org.prebid.server.adapter.rubicon.model.RubiconSiteExtRp;
import org.prebid.server.adapter.rubicon.model.RubiconTargeting;
import org.prebid.server.adapter.rubicon.model.RubiconTargetingExt;
import org.prebid.server.adapter.rubicon.model.RubiconTargetingExtRp;
import org.prebid.server.adapter.rubicon.model.RubiconUserExt;
import org.prebid.server.adapter.rubicon.model.RubiconUserExtRp;
import org.prebid.server.adapter.rubicon.model.RubiconVideoExt;
import org.prebid.server.adapter.rubicon.model.RubiconVideoExtRp;
import org.prebid.server.adapter.rubicon.model.RubiconVideoParams;
import org.prebid.server.exception.PreBidException;
import org.prebid.server.model.AdUnitBid;
import org.prebid.server.model.Bidder;
import org.prebid.server.model.MediaType;
import org.prebid.server.model.PreBidRequestContext;
import org.prebid.server.model.request.PreBidRequest;
import org.prebid.server.model.request.Sdk;
import org.prebid.server.model.response.Bid;
import org.prebid.server.model.response.UsersyncInfo;

import java.math.BigDecimal;
import java.util.Base64;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * <a href="https://rubiconproject.com">Rubicon Project</a> {@link org.prebid.server.adapter.Adapter} implementation.
 * <p>
 * Maintainer email: <a href="mailto:header-bidding@rubiconproject.com">header-bidding@rubiconproject.com</a>
 */
public class RubiconAdapter extends OpenrtbAdapter {

    private static final Logger logger = LoggerFactory.getLogger(RubiconAdapter.class);

    private static final Set<MediaType> ALLOWED_MEDIA_TYPES = Collections.unmodifiableSet(
            EnumSet.of(MediaType.banner, MediaType.video));

    private static final String PREBID_SERVER_USER_AGENT = "prebid-server/1.0";

    private final String endpointUrl;
    private final UsersyncInfo usersyncInfo;
    private final String authHeader;

    public RubiconAdapter(String endpointUrl, String usersyncUrl, String xapiUsername, String xapiPassword) {
        this.endpointUrl = validateUrl(Objects.requireNonNull(endpointUrl));

        usersyncInfo = createUsersyncInfo(Objects.requireNonNull(usersyncUrl));

        authHeader = "Basic " + Base64.getEncoder().encodeToString((Objects.requireNonNull(xapiUsername)
                + ':' + Objects.requireNonNull(xapiPassword)).getBytes());
    }

    private static UsersyncInfo createUsersyncInfo(String usersyncUrl) {
        return UsersyncInfo.of(usersyncUrl, "redirect", false);
    }

    @Override
    public String code() {
        return "rubicon";
    }

    @Override
    public String cookieFamily() {
        return "rubicon";
    }

    @Override
    public UsersyncInfo usersyncInfo() {
        return usersyncInfo;
    }

    @Override
    public List<HttpRequest> makeHttpRequests(Bidder bidder, PreBidRequestContext preBidRequestContext) {
        final MultiMap headers = headers()
                .add(HttpHeaders.AUTHORIZATION, authHeader)
                .add(HttpHeaders.USER_AGENT, PREBID_SERVER_USER_AGENT);

        final List<AdUnitBid> adUnitBids = bidder.getAdUnitBids();

        validateAdUnitBidsMediaTypes(adUnitBids);

        final List<HttpRequest> httpRequests = adUnitBids.stream()
                .flatMap(adUnitBid -> createBidRequests(adUnitBid, preBidRequestContext))
                .map(bidRequest -> HttpRequest.of(endpointUrl, headers, bidRequest))
                .collect(Collectors.toList());

        validateBidRequests(httpRequests.stream()
                .map(HttpRequest::getBidRequest)
                .collect(Collectors.toList()));

        return httpRequests;
    }

    private static void validateBidRequests(List<BidRequest> bidRequests) {
        if (bidRequests.size() == 0) {
            throw new PreBidException("Invalid ad unit/imp");
        }
    }

    private Stream<BidRequest> createBidRequests(AdUnitBid adUnitBid, PreBidRequestContext preBidRequestContext) {
        final RubiconParams rubiconParams = parseAndValidateRubiconParams(adUnitBid);
        final PreBidRequest preBidRequest = preBidRequestContext.getPreBidRequest();
        return makeImps(adUnitBid, rubiconParams, preBidRequestContext)
                .map(imp -> BidRequest.builder()
                        .id(preBidRequest.getTid())
                        .app(makeApp(rubiconParams, preBidRequestContext))
                        .at(1)
                        .tmax(preBidRequest.getTimeoutMillis())
                        .imp(Collections.singletonList(imp))
                        .site(makeSite(rubiconParams, preBidRequestContext))
                        .device(makeDevice(preBidRequestContext))
                        .user(makeUser(rubiconParams, preBidRequestContext))
                        .source(makeSource(preBidRequestContext))
                        .build());
    }

    private RubiconParams parseAndValidateRubiconParams(AdUnitBid adUnitBid) {
        final ObjectNode params = adUnitBid.getParams();
        if (params == null) {
            throw new PreBidException("Rubicon params section is missing");
        }

        final RubiconParams rubiconParams;
        try {
            rubiconParams = Json.mapper.convertValue(params, RubiconParams.class);
        } catch (IllegalArgumentException e) {
            // a weird way to pass parsing exception
            throw new PreBidException(e.getMessage(), e.getCause());
        }

        final Integer accountId = rubiconParams.getAccountId();
        final Integer siteId = rubiconParams.getSiteId();
        final Integer zoneId = rubiconParams.getZoneId();
        if (accountId == null || accountId == 0) {
            throw new PreBidException("Missing accountId param");
        } else if (siteId == null || siteId == 0) {
            throw new PreBidException("Missing siteId param");
        } else if (zoneId == null || zoneId == 0) {
            throw new PreBidException("Missing zoneId param");
        }

        return rubiconParams;
    }

    private static App makeApp(RubiconParams rubiconParams, PreBidRequestContext preBidRequestContext) {
        final App app = preBidRequestContext.getPreBidRequest().getApp();
        return app == null ? null : app.toBuilder()
                .publisher(makePublisher(rubiconParams))
                .ext(Json.mapper.valueToTree(makeSiteExt(rubiconParams)))
                .build();
    }

    private static Stream<Imp> makeImps(AdUnitBid adUnitBid, RubiconParams rubiconParams,
                                        PreBidRequestContext preBidRequestContext) {
        return allowedMediaTypes(adUnitBid, ALLOWED_MEDIA_TYPES).stream()
                .filter(mediaType -> isValidAdUnitBidMediaType(mediaType, adUnitBid))
                .map(mediaType -> impBuilderWithMedia(mediaType, adUnitBid, rubiconParams))
                .map(impBuilder -> impBuilder
                        .id(adUnitBid.getAdUnitCode())
                        .secure(preBidRequestContext.getSecure())
                        .instl(adUnitBid.getInstl())
                        .ext(Json.mapper.valueToTree(makeImpExt(rubiconParams, preBidRequestContext)))
                        .build());
    }

    private static boolean isValidAdUnitBidMediaType(MediaType mediaType, AdUnitBid adUnitBid) {
        switch (mediaType) {
            case video:
                final org.prebid.server.model.request.Video video = adUnitBid.getVideo();
                return video != null && !CollectionUtils.isEmpty(video.getMimes());
            case banner:
                return adUnitBid.getSizes().stream().map(RubiconSize::toId).anyMatch(id -> id > 0);
            default:
                return false;
        }
    }

    private static Imp.ImpBuilder impBuilderWithMedia(MediaType mediaType, AdUnitBid adUnitBid,
                                                      RubiconParams rubiconParams) {
        final Imp.ImpBuilder builder = Imp.builder();
        switch (mediaType) {
            case video:
                builder.video(makeVideo(adUnitBid, rubiconParams.getVideo()));
                break;
            case banner:
                builder.banner(makeBanner(adUnitBid));
                break;
            default:
                // unknown media type, just skip it
        }
        return builder;
    }

    private static RubiconImpExt makeImpExt(RubiconParams rubiconParams, PreBidRequestContext preBidRequestContext) {
        return RubiconImpExt.of(RubiconImpExtRp.of(rubiconParams.getZoneId(), makeInventory(rubiconParams),
                makeImpExtRpTrack(preBidRequestContext)));
    }

    private static JsonNode makeInventory(RubiconParams rubiconParams) {
        final JsonNode inventory = rubiconParams.getInventory();
        return !inventory.isNull() ? inventory : null;
    }

    private static RubiconImpExtRpTrack makeImpExtRpTrack(PreBidRequestContext preBidRequestContext) {
        final Sdk sdk = preBidRequestContext.getPreBidRequest().getSdk();
        final String mintVersion;
        if (sdk != null) {
            mintVersion = String.format("%s_%s_%s", StringUtils.defaultString(sdk.getSource()),
                    StringUtils.defaultString(sdk.getPlatform()), StringUtils.defaultString(sdk.getVersion()));
        } else {
            mintVersion = "__";
        }

        return RubiconImpExtRpTrack.of("prebid", mintVersion);
    }

    private static Banner makeBanner(AdUnitBid adUnitBid) {
        return bannerBuilder(adUnitBid)
                .ext(Json.mapper.valueToTree(makeBannerExt(adUnitBid.getSizes())))
                .build();
    }

    private static Video makeVideo(AdUnitBid adUnitBid, RubiconVideoParams rubiconVideoParams) {
        return videoBuilder(adUnitBid)
                .ext(rubiconVideoParams != null ? Json.mapper.valueToTree(makeVideoExt(rubiconVideoParams)) : null)
                .build();
    }

    private static RubiconVideoExt makeVideoExt(RubiconVideoParams rubiconVideoParams) {
        return RubiconVideoExt.of(rubiconVideoParams.getSkip(), rubiconVideoParams.getSkipdelay(),
                RubiconVideoExtRp.of(rubiconVideoParams.getSizeId()));
    }

    private static RubiconBannerExt makeBannerExt(List<Format> sizes) {
        final List<Integer> validRubiconSizeIds = sizes.stream()
                .map(RubiconSize::toId)
                .filter(id -> id > 0)
                .collect(Collectors.toList());

        return RubiconBannerExt.of(RubiconBannerExtRp.of(
                validRubiconSizeIds.get(0),
                validRubiconSizeIds.size() > 1 ? validRubiconSizeIds.subList(1, validRubiconSizeIds.size()) : null,
                "text/html"));
    }

    private Site makeSite(RubiconParams rubiconParams, PreBidRequestContext preBidRequestContext) {
        Site.SiteBuilder siteBuilder = siteBuilder(preBidRequestContext);
        if (siteBuilder == null) {
            siteBuilder = Site.builder();
        }

        final PreBidRequest preBidRequest = preBidRequestContext.getPreBidRequest();
        if (preBidRequest.getApp() != null) {
            final User user = preBidRequest.getUser();
            final String language = user != null ? user.getLanguage() : null;
            siteBuilder
                    .content(Content.builder().language(language).build());
        } else {
            siteBuilder
                    .publisher(makePublisher(rubiconParams))
                    .ext(Json.mapper.valueToTree(makeSiteExt(rubiconParams)));
        }

        return siteBuilder.build();
    }

    private static RubiconSiteExt makeSiteExt(RubiconParams rubiconParams) {
        return RubiconSiteExt.of(RubiconSiteExtRp.of(rubiconParams.getSiteId()));
    }

    private static Publisher makePublisher(RubiconParams rubiconParams) {
        return Publisher.builder()
                .ext(Json.mapper.valueToTree(makePublisherExt(rubiconParams)))
                .build();
    }

    private static RubiconPubExt makePublisherExt(RubiconParams rubiconParams) {
        return RubiconPubExt.of(RubiconPubExtRp.of(rubiconParams.getAccountId()));
    }

    private static Device makeDevice(PreBidRequestContext preBidRequestContext) {
        return deviceBuilder(preBidRequestContext)
                .ext(Json.mapper.valueToTree(makeDeviceExt(preBidRequestContext)))
                .build();
    }

    private static RubiconDeviceExt makeDeviceExt(PreBidRequestContext preBidRequestContext) {
        final Device device = preBidRequestContext.getPreBidRequest().getDevice();
        final BigDecimal pixelratio = device != null ? device.getPxratio() : null;

        return RubiconDeviceExt.of(RubiconDeviceExtRp.of(pixelratio));
    }

    private User makeUser(RubiconParams rubiconParams, PreBidRequestContext preBidRequestContext) {
        User.UserBuilder userBuilder = userBuilder(preBidRequestContext);
        if (userBuilder == null) {
            final User user = preBidRequestContext.getPreBidRequest().getUser();
            userBuilder = user != null ? user.toBuilder() : User.builder();
        }

        final RubiconUserExt userExt = makeUserExt(rubiconParams);
        return userExt != null
                ? userBuilder.ext(Json.mapper.valueToTree(userExt)).build()
                : userBuilder.build();
    }

    private static RubiconUserExt makeUserExt(RubiconParams rubiconParams) {
        final JsonNode visitor = rubiconParams.getVisitor();
        return !visitor.isNull() ? RubiconUserExt.of(RubiconUserExtRp.of(visitor), null) : null;
    }

    @Override
    public List<Bid.BidBuilder> extractBids(Bidder bidder, ExchangeCall exchangeCall) {
        return responseBidStream(exchangeCall.getBidResponse())
                .filter(bid -> bid.getPrice() != null && bid.getPrice().compareTo(BigDecimal.ZERO) != 0)
                .map(bid -> toBidBuilder(bid, bidder, mediaTypeFor(exchangeCall.getBidRequest())))
                .limit(1) // one bid per request/response
                .collect(Collectors.toList());
    }

    private static MediaType mediaTypeFor(BidRequest bidRequest) {
        final MediaType mediaType = MediaType.banner;
        if (bidRequest != null && CollectionUtils.isNotEmpty(bidRequest.getImp())) {
            if (bidRequest.getImp().get(0).getVideo() != null) {
                return MediaType.video;
            }
        }
        return mediaType;
    }

    private static Bid.BidBuilder toBidBuilder(com.iab.openrtb.response.Bid bid, Bidder bidder, MediaType mediaType) {
        final AdUnitBid adUnitBid = lookupBid(bidder.getAdUnitBids(), bid.getImpid());
        return Bid.builder()
                .bidder(adUnitBid.getBidderCode())
                .bidId(adUnitBid.getBidId())
                .code(bid.getImpid())
                .price(bid.getPrice())
                .adm(bid.getAdm())
                .creativeId(bid.getCrid())
                .mediaType(mediaType)
                .width(bid.getW())
                .height(bid.getH())
                .dealId(bid.getDealid())
                .adServerTargeting(toAdServerTargetingOrNull(bid));
    }

    private static Map<String, String> toAdServerTargetingOrNull(com.iab.openrtb.response.Bid bid) {
        RubiconTargetingExt rubiconTargetingExt = null;
        try {
            rubiconTargetingExt = Json.mapper.convertValue(bid.getExt(), RubiconTargetingExt.class);
        } catch (IllegalArgumentException e) {
            logger.warn("Exception occurred while de-serializing rubicon targeting extension", e);
        }

        final RubiconTargetingExtRp rp = rubiconTargetingExt != null ? rubiconTargetingExt.getRp() : null;
        final List<RubiconTargeting> targeting = rp != null ? rp.getTargeting() : null;
        return targeting != null
                ? targeting.stream().collect(Collectors.toMap(RubiconTargeting::getKey, t -> t.getValues().get(0)))
                : null;
    }

    @Override
    public boolean tolerateErrors() {
        return true;
    }
}
