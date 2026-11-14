package com.bookbuddy.safety;

import com.bookbuddy.auth.UserDocument;
import com.bookbuddy.config.BookBuddyProperties;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/** Builds the warm, static crisis reply and the list of helplines to show alongside it. */
@Service
public class CrisisResponseService {

    /** Used only if the deployment configured no resources at all: a crisis reply must never be empty-handed. */
    private static final List<BookBuddyProperties.CrisisResource> FALLBACK = List.of(
            new BookBuddyProperties.CrisisResource("Tele-MANAS", "14416"),
            new BookBuddyProperties.CrisisResource("Emergency", "112"));

    static final String MESSAGE_EN = """
            I'm really glad you told me, and I'm so sorry you're carrying this much pain right now. You don't have to \
            face it alone.

            If you might act on these thoughts, or you're in danger, please call your local emergency number right \
            now, or reach out to one of the helplines below — they are free and there is someone to talk to. If \
            you can, tell someone close to you where you are and how you feel, and try to put some distance between \
            you and anything you could use to hurt yourself.

            I'm here to keep you company, but a real person can help in ways I can't. Would you like to tell me a \
            little about what's happening?""";

    static final String MESSAGE_HI = """
            आपने मुझे बताया, इसके लिए शुक्रिया — और मुझे बहुत अफ़सोस है कि आप इतना दर्द झेल रहे हैं। आपको इसे अकेले \
            नहीं सहना है।

            अगर आपको लगता है कि आप खुद को नुकसान पहुँचा सकते हैं, या आप खतरे में हैं, तो कृपया अभी अपने स्थानीय \
            आपातकालीन नंबर पर कॉल करें, या नीचे दी गई हेल्पलाइन पर बात करें — ये मुफ़्त हैं और कोई आपकी बात सुनने के \
            लिए मौजूद है। हो सके तो अपने किसी करीबी को बताइए कि आप कहाँ हैं और कैसा महसूस कर रहे हैं।

            मैं आपके साथ हूँ, पर कोई इंसान वह मदद कर सकता है जो मैं नहीं कर सकता। क्या आप बताना चाहेंगे कि क्या हो रहा है?""";

    private final BookBuddyProperties properties;

    public CrisisResponseService(BookBuddyProperties properties) {
        this.properties = properties;
    }

    public record Resource(String name, String phone, boolean personal) {}

    public record CrisisResponse(String message, List<Resource> resources) {}

    /**
     * @param user may be null; their personal crisis contacts are listed first
     * @param language {@code "hi"} selects the Hindi message; anything else falls back to English
     */
    public CrisisResponse build(UserDocument user, String language) {
        return new CrisisResponse(message(language), resources(user));
    }

    public List<Resource> resources(UserDocument user) {
        List<Resource> out = new ArrayList<>();
        if (user != null && user.getCrisisContacts() != null) {
            for (UserDocument.CrisisContact c : user.getCrisisContacts()) {
                if (c != null && c.phone() != null && !c.phone().isBlank()) {
                    out.add(new Resource(c.name() == null || c.name().isBlank() ? "Trusted contact" : c.name(),
                            c.phone(), true));
                }
            }
        }
        List<BookBuddyProperties.CrisisResource> configured = properties.safety().crisisResources();
        for (BookBuddyProperties.CrisisResource r : configured.isEmpty() ? FALLBACK : configured) {
            out.add(new Resource(r.name(), r.phone(), false));
        }
        return out;
    }

    public static String message(String language) {
        return language != null && language.toLowerCase().startsWith("hi") ? MESSAGE_HI : MESSAGE_EN;
    }
}
