package com.knightowlgames.proxypal.image;

import com.knightowlgames.proxypal.HttpManager;
import com.knightowlgames.proxypal.datatype.MagicCard;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.Response;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

@Controller
public class ImageRetriever {

    @RequestMapping(value = "/getImage/{cardName}/{setId}", method = RequestMethod.GET)
    public ResponseEntity<String> getImage(@PathVariable("cardName") String cardName,
                                           @PathVariable("setId") String setId) throws IOException
    {
        downloadImage(cardName,setId);
        return new ResponseEntity<>("<a href='./images/" + setId.toUpperCase() + "/" + cardName + ".jpg'>" + cardName + "</a>", HttpStatus.OK);
    }

    @RequestMapping(value = "/getImages/{setName}/{setId}", method = RequestMethod.GET)
    public ResponseEntity<String> getBulkImages(@PathVariable("setName") String setName,
                                                @PathVariable("setId") String setId)
    {
        Set<String> setList;
        Set<String> singleWordFailures = new HashSet<>();
        try {
            setList = downloadSetList(setName);
            System.out.println(setList.toString());
            setList.forEach(cardName -> {
                try {
                    if(!downloadImage(cardName, setId))
                    {
                        if (!cardName.contains(" ")) {
                            singleWordFailures.add(cardName);
                        }
                    }
                } catch (IOException e) {
                    System.out.println("Unable to get " + cardName);
                    if (!cardName.contains(" ")) {
                        singleWordFailures.add(cardName);
                    }
                }
            });
        }
        catch (IOException e)
        {
            System.out.println("Unable to get set list for " + setName);
            e.printStackTrace();
            setList = new HashSet<>();
        }

        singleWordFailures.forEach(first ->
            singleWordFailures.forEach(second -> {
                try {
                    downloadImage(first + "--" + second, setId);
                }
                catch (IOException e)
                {
                    //ignore
                }
            })
        );

        return new ResponseEntity<>(setList.stream()
                .map(s ->  s.replace(" ", "+"))
                .collect(Collectors.joining("\n")), HttpStatus.OK);
    }

    private Set<String> downloadSetList(String setName) throws IOException
    {
        Set<String> setList = new HashSet<>();
        for(int i = 0; i < 4; i++)
        {
            System.out.println("http://gatherer.wizards.com/Pages/Search/Default.aspx?page=" + i + "&output=checklist&set=[%22" + setName.replace("+","%20")+ "%22]");
            Document doc = Jsoup.connect("http://gatherer.wizards.com/Pages/Search/Default.aspx?page=" + i + "&output=checklist&set=[%22" + setName.replace("+","%20")+ "%22]").get();
            Elements cardNames = doc.body().getElementsByClass("nameLink");
            setList.addAll(cardNames.eachText());
        }

        return setList;
    }

    private boolean downloadImage(String cardName, String setId) throws IOException
    {
        OkHttpClient client = HttpManager.getHttpClient();
        String url = "https://cdn1.mtggoldfish.com/images/gf/"
                + cardName
                    .replace("+", "%2B")
                    .replace(" ", "%2B")
                    .replace("--", "%2B%252F%252F%2B")
                    .replace("'", "%2527")
                + "%2B%255B" + setId.toUpperCase() + "%255D.jpg";
        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();
        Response response = client.newCall(request).execute();
        if(response.code()== 403)
        {
            response.body().close();
            return false;
        }
        Path path = Paths.get("./images/" + setId.toUpperCase() + "/");
        //if directory exists?
        if (!Files.exists(path)) {
            try {
                Files.createDirectories(path);
            } catch (IOException e) {
                e.printStackTrace();
                return false;
            }
        }
        String imgPath = ".images/" + setId.toUpperCase() + "/" + cardName.replace(" ", "+") + ".jpg";
        try {
            if(Files.exists(Paths.get(imgPath)))
            {
                System.out.println("File Exists: " + cardName.replace(" ", "+") + ".jpg");
            }
            else {
                System.out.println(url);
                Files.copy(response.body().byteStream(), Paths.get(imgPath));
            }
        }
        catch (FileAlreadyExistsException e)
        {
            System.out.println("File Exists: " + cardName.replace(" ", "+") + ".jpg");
        }
        catch (SocketTimeoutException e)
        {
            if(Files.exists(Paths.get(imgPath)))
            {
                Files.delete(Paths.get(imgPath));

            }
        }
        finally {
            response.body().close();
        }
        return true;
    }

    @RequestMapping("/getDeck/{id}")
    public ResponseEntity<String> getNetDeck(@PathVariable("id") Integer id) throws IOException
    {
        System.out.println("https://www.mtggoldfish.com/deck/" + id + "#paper");
        Document doc = Jsoup.connect("https://www.mtggoldfish.com/deck/" + id + "#paper").get();

        Elements rows = doc.select("#tab-paper .deck-view-deck-table tbody tr");
        Map<String, Integer> deckInfo = new HashMap<>();
        rows.forEach(tr -> {
                if(!tr.select("td").hasClass("deck-header")) {
                    Integer qty = Integer.parseInt(tr.select(".deck-col-qty").text());
                    deckInfo.put(tr.select(".deck-col-card a").attr("data-full-image"), qty);
                    if (tr.select(".deck-col-card a").hasAttr("data-full-image1")) {
                        deckInfo.put(tr.select(".deck-col-card a").attr("data-full-image1"), qty);
                    }
                }
            });
        deckInfo.forEach((link, qty) -> {
            try {
                downloadImageFromLink(link, id.toString());
            }
            catch (IOException e) {
                System.out.println("IOException for: " + link);
                e.printStackTrace();
            }
        });

        List<MagicCard> deckList = new ArrayList<>();
        deckInfo.forEach((name, qty) -> {
            MagicCard card = new MagicCard();
            card.setName(name.substring(name.indexOf("gf/") + 3, name.indexOf("%2B%255B"))
                    .replace("%2B%252F%252F%2B", "--")
                    .replace("%2527", "'")
                    .replace("%252C", ",")
                    .replace("%253E", "")
                    .replace("%253C", "")
                    .replace("%2B", "+"));
            card.setOwned(0);
            card.setUsed(qty);
            if(!card.getName().matches("(Plains|Island|Swamp|Mountain|Forest)[+]+[0-9]*")) {
                System.out.println(card.getName());
                deckList.add(card);
            }
        });

        imageStitcher(id.toString(), deckList, 3, 3);
        return new ResponseEntity<>(deckInfo.toString(),HttpStatus.OK);
    }

    private void downloadImageFromLink(String link, String deckName) throws IOException
    {
        OkHttpClient client = HttpManager.getHttpClient();
        String cardName = link.substring(link.indexOf("gf/") + 3, link.indexOf("%2B%255B"))
                .replace("%2B%252F%252F%2B", "--")
                .replace("%2527", "'")
                .replace("%252C", ",")
                .replace("%253E", "")
                .replace("%253C", "")
                .replace("%2B", "+") + ".jpg";

        Request request = new Request.Builder()
                .url(link)
                .get()
                .build();
        Response response = client.newCall(request).execute();
        Path path = Paths.get("images/" + deckName + "/");

        if (!Files.exists(path)) {
            try {
                Files.createDirectories(path);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        String imgPath = "images/" + deckName + "/" + cardName;
        try {
            if(Files.exists(Paths.get(imgPath)))
            {
                System.out.println("File Exists: " + cardName);
            }
            else {
                System.out.println(Paths.get(imgPath).toAbsolutePath());
                Files.copy(response.body().byteStream(), Paths.get(imgPath).toAbsolutePath());
            }
        }
        catch (FileAlreadyExistsException e)
        {
            System.out.println("File Exists: " + cardName.replace(" ", "+") + ".jpg");
        }
        catch (SocketTimeoutException e)
        {
            if(Files.exists(Paths.get(imgPath)))
            {
                Files.delete(Paths.get(imgPath));

            }
        }
        finally {
            response.body().close();
        }
    }

    private void imageStitcher(String deckname, List<MagicCard> cardSet, int imagesWide, int imagesTall) throws IOException
    {
        if(imagesWide < 1 || imagesTall < 1)
        {
            return;
        }
        File deckFolder = new File("images/" + deckname + "/deck/");
        String[]entries = deckFolder.list();
        if(entries != null) {
            for (String entry : entries) {
                File currentFile = new File(deckFolder.getPath(), entry);
                currentFile.delete();
            }
        }
        if (!Files.exists(deckFolder.toPath())) {
            try {
                Files.createDirectories(deckFolder.toPath());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        int imgCount = 0;
        int imgNumber = 0;
        int page = 1;
        while(cardSet.get(imgNumber).getOwned() >= cardSet.get(imgNumber).getUsed() && imgNumber < cardSet.size())
        {
            imgNumber ++;
        }

        while(imgNumber < cardSet.size())
        {
            BufferedImage compiledImage = new BufferedImage((imagesWide * scaledWidth) + (3 * (imagesWide -1)),
                    (imagesTall * scaledHeight) + (3 * (imagesTall - 1)),
                    BufferedImage.TYPE_INT_RGB);
            Graphics2D g2d = compiledImage.createGraphics();
            g2d.setBackground(Color.WHITE);
            g2d.clearRect(0, 0, compiledImage.getWidth(), compiledImage.getHeight());
            for (int i = 0; i < imagesTall; i++)
            {
                for (int j = 0; j < imagesWide; j++)
                {
                    if(imgNumber >= cardSet.size())
                    {
                        break;
                    }
                    try {
                        File image = new File("images/" + deckname + "/" + cardSet.get(imgNumber).getName() + ".jpg");
                        compiledImage.createGraphics().drawImage(adjustImage(image), j * (3 + scaledWidth), i * (3 + scaledHeight), null);
                    }
                    catch (IOException e)
                    {
                        //ignore
                    }
                    finally {
                        imgCount ++;
                        while(imgNumber < cardSet.size() && cardSet.get(imgNumber).getOwned() + imgCount >= cardSet.get(imgNumber).getUsed())
                        {
                            imgNumber ++;
                            imgCount = 0;
                        }
                    }
                    if(imgNumber >= cardSet.size())
                    {
                        break;
                    }
                }
                if(imgNumber >= cardSet.size())
                {
                    break;
                }
            }
            ImageIO.write(compiledImage, "png", new File("images/" + deckname + "/deck/page" + page + ".png"));
            page ++;

        }
    }

    private BufferedImage adjustImage(File imageFile)
            throws IOException {
        return adjustImage(imageFile, true, false);
    }

    private final int scaledWidth = 200;
    private final int scaledHeight = 280;
    //final size is 200 width x 280 height
    private BufferedImage adjustImage(File imageFile, boolean grayscale, boolean desaturate)
            throws IOException {

        BufferedImage image = ImageIO.read(imageFile);
        Image scaledImage = image.getScaledInstance(scaledWidth, scaledHeight, Image.SCALE_SMOOTH);
        BufferedImage finalImage = new BufferedImage(scaledWidth, scaledHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D bGr = finalImage.createGraphics();
        bGr.drawImage(scaledImage, 0, 0, null);
        bGr.dispose();
        if(grayscale)
        {
            finalImage = grayscale(finalImage);
        }

        return finalImage;
    }

    //from www.dyclassroom.com/image-processing-project/how-to-convert-a-color-image-into-grayscale-image-in-java
    private BufferedImage grayscale(BufferedImage image)
    {
        //get image width and height
        int width = image.getWidth();
        int height = image.getHeight();

        //convert to grayscale
        for(int y = 0; y < height; y++){
            for(int x = 0; x < width; x++){
                int p = image.getRGB(x,y);

                int a = (p>>24)&0xff;
                int r = (p>>16)&0xff;
                int g = (p>>8)&0xff;
                int b = p&0xff;

                //calculate average
                int avg = (r+g+b)/3;

//                if(avg < 48)
//                {
//                    avg = 0;
//                }
//                else if(avg > 207)
//                {
//                    avg = 255;
//                }
                //replace RGB value with avg
                p = (a<<24) | (avg<<16) | (avg<<8) | avg;

                image.setRGB(x, y, p);
            }
        }
        return image;
    }
}
